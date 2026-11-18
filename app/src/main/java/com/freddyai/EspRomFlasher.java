package com.freddyai;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** ESP32-S2/S3/C3 ROM protocol; no stub or eFuse writes. Independent of Android. */
public final class EspRomFlasher {
    public interface Link { void write(byte[] bytes) throws Exception; int readByte(long deadlineNanos) throws Exception; }
    public interface Progress { void update(int percent, String message); boolean cancelled(); }
    public static final class Segment {
        public final int offset; public final byte[] bytes;
        public Segment(int address, byte[] data) { offset=address;bytes=data.clone(); }
    }
    private final Link link; private final Progress progress;
    private int chip;
    public EspRomFlasher(Link transport,Progress listener){link=transport;progress=listener;}
    public static byte[] words(int... values){ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(int v:values)b.putInt(v);return b.array();}
    public static int word(byte[] data,int offset){return ByteBuffer.wrap(data,offset,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    public static String digest(String algorithm,byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance(algorithm).digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    public static byte[] slip(byte[] data){ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(0xc0);for(byte b:data){int v=b&255;if(v==0xc0){out.write(0xdb);out.write(0xdc);}else if(v==0xdb){out.write(0xdb);out.write(0xdd);}else out.write(v);}out.write(0xc0);return out.toByteArray();}
    private void checkCancel()throws InterruptedIOException{if(progress.cancelled())throw new InterruptedIOException("Запись отменена. Плата остаётся в загрузчике; прошей её заново.");}
    private byte[] frame(long deadline)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean start=false,escape=false;
        while(System.nanoTime()<deadline){checkCancel();int v=link.readByte(deadline);if(v<0)continue;
            if(v==0xc0){if(start&&out.size()>0)return out.toByteArray();start=true;escape=false;continue;}
            if(!start)continue;if(escape){if(v==0xdc)v=0xc0;else if(v==0xdd)v=0xdb;else throw new IOException("Повреждён USB-пакет.");escape=false;}
            else if(v==0xdb){escape=true;continue;}out.write(v);if(out.size()>65543)throw new IOException("Слишком длинный USB-пакет.");
        }throw new IOException("Загрузчик не ответил. Удерживай BOOT, нажми RESET, отпусти BOOT и повтори.");
    }
    private byte[] command(int opcode,byte[] payload,int checksum,int timeout)throws Exception{
        checkCancel();ByteBuffer header=ByteBuffer.allocate(8+payload.length).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte)0).put((byte)opcode).putShort((short)payload.length).putInt(checksum).put(payload);link.write(slip(header.array()));
        long deadline=System.nanoTime()+timeout*1000000L;
        while(System.nanoTime()<deadline){byte[] response=frame(deadline);if(response.length<8||response[0]!=1||(response[1]&255)!=opcode)continue;
            int length=(response[2]&255)|((response[3]&255)<<8);if(length<4||response.length!=length+8)throw new IOException("Неверная длина ответа ROM.");
            if(response[response.length-4]!=0)throw new IOException("ROM отклонил команду 0x"+Integer.toHexString(opcode)+", ошибка "+(response[response.length-3]&255));return response;
        }throw new IOException("Истёк таймаут ROM.");
    }
    public int detect()throws Exception{
        byte[] sync=new byte[36];Arrays.fill(sync,(byte)0x55);sync[0]=7;sync[1]=7;sync[2]=0x12;sync[3]=0x20;
        Exception last=null;for(int i=0;i<5;i++){try{command(8,sync,0,1200);last=null;break;}catch(InterruptedIOException e){throw e;}catch(Exception e){last=e;}}if(last!=null)throw last;
        byte[] security=command(0x14,new byte[0],0,3000);int size=security.length-12;
        if(size!=12&&size!=20)throw new IOException("Неизвестный формат GET_SECURITY_INFO.");
        int flags=word(security,8),crypt=security[12]&255;
        if((flags&5)!=0||(Integer.bitCount(crypt)&1)!=0)throw new IOException("Secure Boot / шифрование / secure download: обычная запись заблокирована.");
        if(size==12){byte[] reg=command(0x0a,words(0x40001000),0,3000);if(word(reg,4)!=0x7c6)throw new IOException("Это не ESP32-S2.");chip=2;}
        else chip=word(security,20);
        if(chip!=2&&chip!=5&&chip!=9)throw new IOException("Этот чип пока не поддержан USB-загрузчиком: "+chip);
        return chip;
    }
    public static void validate(List<Segment> segments,int expectedChip,int flashSize)throws IOException{
        if(expectedChip!=2&&expectedChip!=5&&expectedChip!=9)throw new IOException("Поддержаны ESP32-S2, ESP32-S3 и ESP32-C3.");
        if(flashSize<1048576||flashSize>16777216||(flashSize&(flashSize-1))!=0)throw new IOException("Неверный размер Flash.");
        if(segments.isEmpty()||segments.size()>16)throw new IOException("Пустой или слишком большой пакет.");
        List<Segment> ordered=new ArrayList<>(segments);ordered.sort(Comparator.comparingInt(s->s.offset));long end=-1;boolean image=false;
        for(Segment s:ordered){long padded=((s.bytes.length+1023L)/1024)*1024,eraseEnd=((s.offset+padded+4095)/4096)*4096;
            if(s.offset<0||(s.offset&4095)!=0||s.bytes.length==0||s.offset<end||eraseEnd>flashSize)throw new IOException("Пересечение секторов, неверный адрес или выход за Flash.");end=eraseEnd;
            if((s.bytes[0]&255)==0xe9){if(s.bytes.length<24)throw new IOException("Обрезанный заголовок ESP.");int id=(s.bytes[12]&255)|((s.bytes[13]&255)<<8);if(id!=expectedChip)throw new IOException("Бинарный файл для другого чипа.");image=true;}
        }if(!image)throw new IOException("В пакете нет образа ESP.");
    }
    public void flash(List<Segment> segments,int expectedChip,int flashSize)throws Exception{
        validate(segments,expectedChip,flashSize);progress.update(0,"Проверка чипа");int found=detect();if(found!=expectedChip)throw new IOException("Подключён другой чип. Запись не начата.");
        command(0x0d,words(0,0),0,3000);command(0x0b,words(0,flashSize,65536,4096,256,65535),0,3000);
        long total=0,done=0;for(Segment s:segments)total+=s.bytes.length;
        for(Segment s:segments){int blocks=(s.bytes.length+1023)/1024;progress.update((int)(done*90/total),"Стирание секторов 0x"+Integer.toHexString(s.offset));
            command(2,words(s.bytes.length,blocks,1024,s.offset,0),0,Math.max(30000,s.bytes.length/1048576*30000));
            for(int n=0;n<blocks;n++){checkCancel();byte[] block=new byte[1024];Arrays.fill(block,(byte)255);int length=Math.min(1024,s.bytes.length-n*1024);System.arraycopy(s.bytes,n*1024,block,0,length);
                ByteArrayOutputStream data=new ByteArrayOutputStream();data.write(words(1024,n,0,0));data.write(block);int sum=0xef;for(byte v:block)sum^=v&255;
                command(3,data.toByteArray(),sum,10000);done+=length;progress.update((int)(done*90/total),"Запись прошивки");
            }
            byte[] reply=command(0x13,words(s.offset,s.bytes.length,0,0),0,30000);if(reply.length!=44)throw new IOException("ROM не вернул MD5.");
            String remote=new String(reply,8,32,StandardCharsets.US_ASCII);if(!remote.equalsIgnoreCase(digest("MD5",s.bytes)))throw new IOException("Проверка записанной памяти не совпала. Успех не подтверждён.");
        }
        progress.update(100,"Запись и MD5 проверены. Нажми RESET на плате.");
        // Stay in ROM until an explicit physical reset. No success is based on reboot alone.
    }
}
