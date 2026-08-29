package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Focused scanner for methods that construct audio_karaoke_volume= values. */
public final class NeteaseKaraokeVolumeScanner {
    private static final String PKG="com.netease.cloudmusic";
    private NeteaseKaraokeVolumeScanner(){}

    public static void scanAsync(Context context){
        if(context==null)return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);}catch(Throwable t){AppState.get().log.add("NCM VOLSCAN error: "+t.getClass().getSimpleName());}
        },"ncm-karaoke-volscan").start();
    }

    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        AppState.get().log.add("NCM VOLSCAN start");
        int hits=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize(); if(sz<=0||sz>90L*1024L*1024L)continue;
                    byte[] b=readAll(z.getInputStream(e));
                    hits+=scanDex(e.getName(),b);
                }
            }
        }
        AppState.get().log.add("NCM VOLSCAN done hits="+hits);
    }

    private static int scanDex(String name,byte[] b){
        if(b.length<0x70)return 0;
        int stringsN=i32(b,0x38), stringsOff=i32(b,0x3c), classesN=i32(b,0x60), classesOff=i32(b,0x64);
        if(stringsN<=0||classesN<=0)return 0;
        String[] strings=new String[stringsN]; int target=-1;
        for(int i=0;i<stringsN;i++){
            int p=stringsOff+i*4; if(p<0||p+4>b.length)break;
            int off=i32(b,p); if(off<=0||off>=b.length)continue;
            int[] q={off}; try{uleb(b,q);}catch(Throwable x){continue;}
            int s=q[0],e=s,max=Math.min(b.length,s+500); while(e<max&&b[e]!=0)e++;
            if(e<=s||e>=max)continue;
            String v=new String(b,s,e-s,StandardCharsets.UTF_8); strings[i]=v;
            if(v.equals("audio_karaoke_volume="))target=i;
        }
        if(target<0)return 0;
        int hits=0;
        for(int ci=0;ci<classesN;ci++){
            int cp=classesOff+ci*32; if(cp<0||cp+32>b.length)break;
            int dataOff=i32(b,cp+24); if(dataOff<=0||dataOff>=b.length)continue;
            int[] p={dataOff};
            try{
                int sf=uleb(b,p),inf=uleb(b,p),dm=uleb(b,p),vm=uleb(b,p);
                for(int i=0;i<sf+inf;i++){uleb(b,p);uleb(b,p);} int idx=0;
                for(int pass=0;pass<2;pass++){
                    int count=pass==0?dm:vm; idx=0;
                    for(int mi=0;mi<count;mi++){
                        idx+=uleb(b,p); uleb(b,p); int codeOff=uleb(b,p);
                        if(codeOff<=0||codeOff+16>b.length)continue;
                        int units=i32(b,codeOff+12); if(units<=0||codeOff+16L+units*2L>b.length)continue;
                        int start=codeOff+16; boolean contains=false; Set<Integer> nums=new LinkedHashSet<>(); Set<String> ss=new LinkedHashSet<>();
                        for(int u=0;u<units;u++){
                            int cu=u16(b,start+u*2),op=cu&0xff;
                            if(op==0x1a&&u+1<units){int si=u16(b,start+(u+1)*2);if(si==target)contains=true;if(si>=0&&si<strings.length&&strings[si]!=null){String s=strings[si];String lo=s.toLowerCase(java.util.Locale.ROOT);if(lo.contains("karaoke")||lo.contains("volume")||lo.contains("vocal")||s.contains("人声")||s.contains("原唱")||s.contains("伴奏"))ss.add(s);}}
                            else if(op==0x12){int lit=(cu>>12)&0xf;if((lit&8)!=0)lit|=~0xf;nums.add(lit);} // const/4
                            else if(op==0x13&&u+1<units){nums.add((int)(short)u16(b,start+(u+1)*2));} // const/16
                            else if(op==0x14&&u+2<units){nums.add(u16(b,start+(u+1)*2)|(u16(b,start+(u+2)*2)<<16));} // const
                        }
                        if(contains){
                            hits++;
                            AppState.get().log.add("NCM VOLSCAN HIT "+name+" method#"+idx+" nums="+trim(nums)+" strings="+trimS(ss));
                        }
                    }
                }
            }catch(Throwable ignored){}
        }
        return hits;
    }

    private static String trim(Set<Integer> s){StringBuilder b=new StringBuilder();for(Integer v:s){if(v>=-1000&&v<=10000){if(b.length()>0)b.append(',');b.append(v);}if(b.length()>220)break;}return b.toString();}
    private static String trimS(Set<String> s){StringBuilder b=new StringBuilder();for(String v:s){if(b.length()>0)b.append('|');b.append(v);if(b.length()>260)break;}return b.toString();}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException();o.write(buf,0,n);}return o.toByteArray();}}
    private static int i32(byte[] b,int p){if(p<0||p+4>b.length)return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
    private static int u16(byte[] b,int p){if(p<0||p+2>b.length)return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
    private static int uleb(byte[] b,int[] pp){int r=0,s=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<s;if((v&128)==0){pp[0]=p;return r;}s+=7;}throw new IllegalArgumentException();}
}
