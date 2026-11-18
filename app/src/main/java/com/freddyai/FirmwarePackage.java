package com.freddyai;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
/** A bounded, hashed package with explicit board and addresses; never extracts ZIP paths. */
public final class FirmwarePackage {
    public String board,name,sourceSha256; public JSONObject requestedOptions; public int chip,flashSize; public final List<EspRomFlasher.Segment> segments=new ArrayList<>();
    public static final int MAX=8*1024*1024;
    public static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Пакет превышает лимит.");out.write(buf,0,n);}return out.toByteArray();}
    public static FirmwarePackage load(File file)throws Exception{
        if(file.length()>MAX)throw new IOException("Пакет больше 8 МБ.");FirmwarePackage result=new FirmwarePackage();
        try(ZipFile zip=new ZipFile(file)){
            HashSet<String> names=new HashSet<>();Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;
            while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();String n=entry.getName();if(!names.add(n)||++count>32||n.contains("..")||n.startsWith("/")||n.contains("\\"))throw new IOException("Некорректная структура ZIP.");}
            ZipEntry meta=zip.getEntry("manifest.json");if(meta==null)throw new IOException("Нужен ZIP прошивки с manifest.json, не архив исходников.");
            JSONObject obj;try(InputStream in=zip.getInputStream(meta)){obj=new JSONObject(new String(read(in,32768),StandardCharsets.UTF_8));}
            if(obj.getInt("format")!=1)throw new IOException("Неизвестная версия пакета.");result.board=obj.getString("board");result.name=obj.optString("name","Прошивка");result.chip=obj.getInt("chipId");result.flashSize=obj.getInt("flashSize");result.sourceSha256=obj.optString("sourceSha256","");result.requestedOptions=obj.optJSONObject("requestedOptions");
            JSONArray list=obj.getJSONArray("segments");if(list.length()>16)throw new IOException("Слишком много частей.");int total=0;
            for(int i=0;i<list.length();i++){JSONObject part=list.getJSONObject(i);String name=part.getString("file");ZipEntry entry=zip.getEntry(name);if(entry==null)throw new IOException("Отсутствует "+name);byte[] bytes;try(InputStream in=zip.getInputStream(entry)){bytes=read(in,MAX-total);}total+=bytes.length;
                if(!EspRomFlasher.digest("SHA-256",bytes).equalsIgnoreCase(part.getString("sha256")))throw new IOException("Контрольная сумма не совпала: "+name);
                result.segments.add(new EspRomFlasher.Segment(part.getInt("offset"),bytes));
            }EspRomFlasher.validate(result.segments,result.chip,result.flashSize);return result;
        }
    }
    public String json(String path)throws JSONException{return new JSONObject().put("path",path).put("board",board).put("name",name).put("chipId",chip).put("flashSize",flashSize).put("sourceSha256",sourceSha256).put("segments",segments.size()).put("requestedOptions",requestedOptions).toString();}
}
