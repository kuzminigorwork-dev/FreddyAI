"""Private Arduino CLI compiler for Freddy. No cloud API; no shell execution.
Run with --work-dir on X:/E: and FREDDY_COMPILER_TOKEN (>=24 chars).
Only the authenticated owner may submit C++ source to this service.
"""
import argparse, hashlib, hmac, io, json, os, re, struct, subprocess, threading, uuid, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_SOURCE=500_000
BUILD_LOCK=threading.Lock()

def chip_for(fqbn):
    if not re.fullmatch(r'esp32:esp32:[A-Za-z0-9_-]+',fqbn):raise ValueError('Поддержаны FQBN ESP32-S2/S3/C3.')
    board=fqbn.rsplit(':',1)[1]
    return 2 if 's2' in board else 9 if 's3' in board else 5 if 'c3' in board else (_ for _ in ()).throw(ValueError('Для этого чипа нет Android-загрузчика.'))

def make_package(board, source, properties, build):
    chip=int(properties['_chip'])
    name=properties['build.project_name'];prefix=build/name
    boot=(build/(name+'.bootloader.bin')).read_bytes();app=(build/(name+'.bin')).read_bytes();partitions=(build/(name+'.partitions.bin')).read_bytes()
    flash_size=int(properties['build.flash_size'].upper().replace('MB',''))*1024*1024
    app_offset=None;ota_offset=None;ota_size=0
    for at in range(0,len(partitions)-31,32):
        magic,kind,sub,offset,length,label,flags=struct.unpack_from('<2sBBII16sI',partitions,at)
        if magic!=b'\xaa\x50':break
        if kind==0 and sub in (0,0x10) and app_offset is None:app_offset=offset
        if kind==1 and sub==0:ota_offset,ota_size=offset,length
    if app_offset is None:raise ValueError('Не найден основной раздел приложения.')
    parts=[('bootloader.bin',int(properties['build.bootloader_addr'],0),boot),('partitions.bin',int(properties.get('build.partitions_addr','0x8000'),0),partitions),('app.bin',app_offset,app)]
    if ota_offset is not None:
        ota=(Path(properties['runtime.platform.path'])/'tools/partitions/boot_app0.bin').read_bytes()
        if len(ota)>ota_size:raise ValueError('Раздел OTA слишком мал.')
        parts.append(('boot_app0.bin',ota_offset,ota))
    end=-1
    for _,address,data in sorted(parts,key=lambda p:p[1]):
        erase_end=(address+len(data)+4095)//4096*4096
        if address%4096 or address<end or erase_end>flash_size:raise ValueError('Части пересекаются или выходят за Flash.')
        end=erase_end
        if data[0]==0xe9 and (len(data)<24 or int.from_bytes(data[12:14],'little')!=chip):raise ValueError('Бинарник собран для другого чипа.')
    manifest={'format':1,'board':board,'name':'Freddy · '+board,'chipId':chip,'flashSize':flash_size,'sourceSha256':hashlib.sha256(source.encode()).hexdigest(),'segments':[{'file':n,'offset':a,'sha256':hashlib.sha256(d).hexdigest()} for n,a,d in parts]}
    result=io.BytesIO()
    with zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False))
        for name,_,data in parts:z.writestr(name,data)
    if len(result.getvalue())>8*1024*1024:raise ValueError('Пакет слишком большой.')
    return result.getvalue(),manifest

def compile_request(request,config):
    source=request['source'];board=request['board'];fqbn=request['fqbn'];chip=chip_for(fqbn)
    if not isinstance(source,str) or len(source.encode())>MAX_SOURCE:raise ValueError('Исходник слишком большой.')
    if request['sourceSha256']!=hashlib.sha256(source.encode()).hexdigest():raise ValueError('Хеш исходника не совпал.')
    aliases={'esp32-s2-mini':'esp32:esp32:lolin_s2_mini','esp32-s3':'esp32:esp32:esp32s3','esp32-c3':'esp32:esp32:esp32c3'}
    if aliases.get(board,board)!=fqbn:raise ValueError('Плата и FQBN не совпадают.')
    options=dict(request.get('options',{}))
    for key,value in options.items():
        if not re.fullmatch(r'[A-Za-z0-9_]+',key) or not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_.-]+',value):raise ValueError('Неверная опция платы.')
    root=Path(config.work_dir).resolve();job=root/'jobs'/uuid.uuid4().hex;job.mkdir(parents=True);assert job.resolve().is_relative_to(root)
    sketch=job/'FreddyAdaptive';sketch.mkdir();(sketch/'FreddyAdaptive.ino').write_text(source,encoding='utf-8')
    build=job/'build';build.mkdir();env=os.environ.copy();env['TMP']=env['TEMP']=str(root/'tmp');(root/'tmp').mkdir(exist_ok=True)
    def run(args):
        result=subprocess.run(args,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=280)
        if result.returncode:raise ValueError((result.stdout+'\n'+result.stderr)[-16000:])
        return result.stdout
    # Actual menu values differ by board: S2 Mini uses 'default', S3/C3 use 'cdc'.
    details=json.loads(run([config.cli,'--config-file',config.config,'board','details','--fqbn',fqbn,'--format','json']))
    for menu in details.get('config_options',[]):
        if menu['option']=='CDCOnBoot':
            enabled=next((v['value'] for v in menu['values'] if v['value_label'].strip().lower()=='enabled'),None)
            if enabled is None:raise ValueError('У выбранной платы нет USB CDC Enabled.')
            options['CDCOnBoot']=enabled
    base=[config.cli,'--config-file',config.config,'compile','--fqbn',fqbn,'--board-options',','.join(k+'='+v for k,v in sorted(options.items())),'--build-path',str(build)]
    props=dict(line.split('=',1) for line in run(base+['--show-properties=expanded',str(sketch)]).splitlines() if '=' in line)
    if props.get('build.cdc_on_boot')!='1':raise ValueError('USB CDC не включён для протокола приложения.')
    run(base+[str(sketch)])
    props['_chip']=str(chip)
    package,manifest=make_package(board,source,props,build)
    # Bind the original requested menu options too, so changing settings invalidates a build.
    manifest['requestedOptions']=dict(request.get('options',{}))
    result=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(package)) as old, zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED) as new:
        for name in old.namelist():new.writestr(name,json.dumps(manifest,ensure_ascii=False) if name=='manifest.json' else old.read(name))
    return result.getvalue(),manifest

def main():
    p=argparse.ArgumentParser();p.add_argument('--cli',required=True);p.add_argument('--config',required=True);p.add_argument('--work-dir',required=True);p.add_argument('--bind',default='127.0.0.1');p.add_argument('--port',type=int,default=8766);config=p.parse_args()
    root=Path(config.work_dir).resolve()
    if os.name=='nt' and root.drive.upper()=='C:':p.error('Рабочая папка должна быть на X:, E: или другом диске, не C:.')
    token=os.environ.get('FREDDY_COMPILER_TOKEN','')
    if len(token)<24:p.error('Задай FREDDY_COMPILER_TOKEN длиной не менее 24 символов.')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass # Do not log credentials or submitted source.
        def reply(self,status,data,headers=None):
            self.send_response(status);self.send_header('Content-Type','application/zip' if status==200 else 'text/plain; charset=utf-8');self.send_header('Content-Length',str(len(data)))
            for key,value in (headers or {}).items():self.send_header(key,value)
            self.end_headers();self.wfile.write(data)
        def do_POST(self):
            if self.path!='/compile':self.reply(404,b'Not found');return
            if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):self.reply(401,b'Unauthorized');return
            if not BUILD_LOCK.acquire(blocking=False):self.reply(429,'Компилятор занят.'.encode());return
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<MAX_SOURCE*6+32768:raise ValueError('Неверная длина запроса.')
                self.connection.settimeout(30);obj=json.loads(self.rfile.read(length));package,manifest=compile_request(obj,config)
                self.reply(200,package,{'X-Source-SHA256':manifest['sourceSha256'],'X-Board':manifest['board']})
            except (ValueError,KeyError,TypeError,subprocess.TimeoutExpired) as e:self.reply(400,str(e).encode())
            except Exception:self.reply(500,'Сборка не выполнена; проверь настройку Arduino CLI.'.encode())
            finally:BUILD_LOCK.release()
        def do_GET(self):
            # Provision only over the local ADB tunnel, never over LAN/Wi-Fi.
            if self.path!='/pair' or config.bind not in ('127.0.0.1','::1') or self.client_address[0] not in ('127.0.0.1','::1'):
                self.reply(404,b'Not found');return
            if self.headers.get('Origin') or self.headers.get('Sec-Fetch-Site'):
                self.reply(403,b'Forbidden');return
            self.reply(200,json.dumps({'token':token}).encode())
    root.mkdir(parents=True,exist_ok=True);print('Private Freddy compiler listening on',config.bind,config.port,flush=True);ThreadingHTTPServer((config.bind,config.port),Handler).serve_forever()
if __name__=='__main__':main()
