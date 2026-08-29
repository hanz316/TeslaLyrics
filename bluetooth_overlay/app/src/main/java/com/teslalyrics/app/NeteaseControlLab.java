package com.teslalyrics.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CONTROL7: one integrated NetEase control lab.
 *
 * Goals:
 * - bind the real exported CMApi binder;
 * - bind UCarService with ACTION_UCAR_CONTROL_AIDL instead of a blank action;
 * - load the installed NetEase AIDL interface classes only for reflection/introspection;
 * - dump interface methods + AIDL TRANSACTION_* constants without sending commands;
 * - search installed DEX string pools for CMAPI/UCar/sep-track command tokens.
 *
 * No unknown Binder transact is sent automatically.
 */
public final class NeteaseControlLab {
    private static final String PKG="com.netease.cloudmusic";
    private static final String CM_SERVICE="com.netease.cloudmusic.third.api.CMApiService";
    private static final String UCAR_SERVICE="com.netease.cloudmusic.module.ucar.UCarService";
    private static final String UCAR_ACTION="ACTION_UCAR_CONTROL_AIDL";
    private static final List<ServiceConnection> CONNECTIONS=new CopyOnWriteArrayList<>();
    private static Context app;
    private static IBinder cmBinder;
    private static IBinder ucarBinder;
    private static Object cmInterface;
    private static Object ucarInterface;
    private static ClassLoader ncmLoader;
    private static boolean staticScanRunning=false;

    private NeteaseControlLab(){}

    public static synchronized void start(Context context){
        if(context==null)return;
        app=context.getApplicationContext();
        AppState.get().log.add("NCM CONTROL7 start: CMAPI + UCar AIDL + reflection");
        reconnect(context);
        startStaticScan();
    }

    public static synchronized void reconnect(Context context){
        if(context!=null)app=context.getApplicationContext();
        if(app==null)return;
        unbindAll();
        cmBinder=null;ucarBinder=null;cmInterface=null;ucarInterface=null;
        AppState.get().log.add("NCM CONTROL7 reconnect");
        bindCmApi();
        bindUCar(UCAR_ACTION,"UCar-AIDL");
    }

    public static synchronized void rescan(Context context){
        if(context!=null)app=context.getApplicationContext();
        AppState.get().log.add("NCM CONTROL7 manual rescan");
        reconnect(context);
        startStaticScan();
    }

    public static synchronized String status(){
        return "CMAPI binder: "+(cmBinder!=null&&cmBinder.isBinderAlive()?"Connected":"Not connected")
                +"\nUCar AIDL: "+(ucarBinder!=null&&ucarBinder.isBinderAlive()?"Connected":"Not connected");
    }

    private static void bindCmApi(){
        bind("CMAPI",new Intent().setComponent(new ComponentName(PKG,CM_SERVICE)),true);
    }

    private static void bindUCar(String action,String label){
        Intent i=new Intent(action).setComponent(new ComponentName(PKG,UCAR_SERVICE));
        bind(label,i,false);
    }

    @SuppressWarnings("deprecation")
    private static void bind(String label,Intent intent,boolean cm){
        try{
            ComponentName component=intent.getComponent();
            if(component==null)return;
            ServiceInfo si=app.getPackageManager().getServiceInfo(component,0);
            AppState.get().log.add("NCM CONTROL7 TRY "+label+" exported="+si.exported+" perm="+safe(si.permission)+" action="+safe(intent.getAction()));
            if(!si.exported){AppState.get().log.add("NCM CONTROL7 SKIP "+label+" not exported");return;}
            if(si.permission!=null&&!si.permission.isEmpty()){
                AppState.get().log.add("NCM CONTROL7 SKIP "+label+" protected="+si.permission);return;
            }
            final ServiceConnection[] holder=new ServiceConnection[1];
            ServiceConnection c=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder service){
                    String desc="";
                    try{desc=service==null?"<null>":safe(service.getInterfaceDescriptor());}catch(Throwable t){desc="<"+t.getClass().getSimpleName()+">";}
                    AppState.get().log.add("NCM CONTROL7 CONNECTED "+label+" class="+(service==null?"null":service.getClass().getName())+" descriptor="+desc);
                    if(cm){cmBinder=service;cmInterface=introspectBinder("CMAPI",service,desc);}else{ucarBinder=service;ucarInterface=introspectBinder("UCAR",service,desc);}
                }
                @Override public void onServiceDisconnected(ComponentName name){AppState.get().log.add("NCM CONTROL7 DISCONNECTED "+label);if(cm)cmBinder=null;else ucarBinder=null;}
                @Override public void onBindingDied(ComponentName name){AppState.get().log.add("NCM CONTROL7 BINDING_DIED "+label);}
                @Override public void onNullBinding(ComponentName name){AppState.get().log.add("NCM CONTROL7 NULL_BINDING "+label);}
            };
            holder[0]=c;CONNECTIONS.add(c);
            boolean ok=app.bindService(intent,c,Context.BIND_AUTO_CREATE);
            AppState.get().log.add("NCM CONTROL7 bindService "+label+" returned="+ok);
            if(!ok)CONNECTIONS.remove(c);
        }catch(Throwable t){AppState.get().log.add("NCM CONTROL7 FAIL "+label+" "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
    }

    private static Object introspectBinder(String label,IBinder binder,String descriptor){
        if(binder==null||descriptor==null||descriptor.isEmpty()||descriptor.startsWith("<"))return null;
        try{
            if(ncmLoader==null){
                Context ncm=app.createPackageContext(PKG,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
                ncmLoader=ncm.getClassLoader();
                AppState.get().log.add("NCM CONTROL7 loader="+(ncmLoader==null?"null":ncmLoader.getClass().getName()));
            }
            Class<?> iface=Class.forName(descriptor,false,ncmLoader);
            dumpMethods(label,iface);
            Object proxy=null;
            for(Class<?> nested:iface.getDeclaredClasses()){
                AppState.get().log.add("NCM CONTROL7 "+label+" nested="+nested.getName());
                dumpTransactions(label,nested);
                Object p=tryAsInterface(nested,binder);
                if(p!=null&&proxy==null)proxy=p;
            }
            if(proxy==null){
                try{
                    Class<?> stub=Class.forName(descriptor+"$Stub",false,ncmLoader);
                    dumpTransactions(label,stub);
                    proxy=tryAsInterface(stub,binder);
                }catch(Throwable ignored){}
            }
            if(proxy!=null){
                AppState.get().log.add("NCM CONTROL7 "+label+" proxy="+proxy.getClass().getName());
                dumpMethods(label+" PROXY",proxy.getClass());
            }else AppState.get().log.add("NCM CONTROL7 "+label+" asInterface not found");
            return proxy;
        }catch(Throwable t){
            AppState.get().log.add("NCM CONTROL7 REFLECT "+label+" fail="+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            return null;
        }
    }

    private static Object tryAsInterface(Class<?> c,IBinder binder){
        try{
            for(Method m:c.getDeclaredMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(Modifier.isStatic(m.getModifiers())&&p.length==1&&IBinder.class.isAssignableFrom(p[0])&&(m.getName().equals("asInterface")||IInterface.class.isAssignableFrom(m.getReturnType()))){
                    m.setAccessible(true);
                    Object o=m.invoke(null,binder);
                    if(o!=null)return o;
                }
            }
        }catch(Throwable ignored){}
        return null;
    }

    private static void dumpMethods(String label,Class<?> c){
        try{
            Method[] ms=c.getDeclaredMethods();int n=0;
            for(Method m:ms){
                if(n++>=45){AppState.get().log.add("NCM CONTROL7 "+label+" methods truncated");break;}
                AppState.get().log.add("NCM CONTROL7 "+label+" METHOD "+methodSig(m));
            }
        }catch(Throwable t){AppState.get().log.add("NCM CONTROL7 "+label+" methods fail="+t.getClass().getSimpleName());}
    }

    private static void dumpTransactions(String label,Class<?> c){
        try{
            int n=0;
            for(Field f:c.getDeclaredFields()){
                String name=f.getName();
                if(f.getType()!=int.class||!(name.startsWith("TRANSACTION_")||name.toLowerCase(Locale.ROOT).contains("transaction")))continue;
                if(n++>=45)break;
                try{f.setAccessible(true);AppState.get().log.add("NCM CONTROL7 "+label+" TX "+name+"="+f.getInt(null));}
                catch(Throwable t){AppState.get().log.add("NCM CONTROL7 "+label+" TX "+name+"=<"+t.getClass().getSimpleName()+">");}
            }
        }catch(Throwable ignored){}
    }

    private static String methodSig(Method m){
        StringBuilder s=new StringBuilder(m.getName()).append('(');
        Class<?>[] p=m.getParameterTypes();
        for(int i=0;i<p.length;i++){if(i>0)s.append(',');s.append(shortType(p[i]));}
        return s.append(")->").append(shortType(m.getReturnType())).toString();
    }

    private static String shortType(Class<?> c){
        if(c==null)return "?";if(c.isArray())return shortType(c.getComponentType())+"[]";
        String n=c.getName();return n.startsWith("java.lang.")?n.substring(10):n;
    }

    private static synchronized void startStaticScan(){
        if(staticScanRunning||app==null)return;
        staticScanRunning=true;
        new Thread(()->{
            try{scanTokens(app);}catch(Throwable t){AppState.get().log.add("NCM CONTROL7 TOKENS error="+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{staticScanRunning=false;}
        },"ncm-control7-tokens").start();
    }

    private static void scanTokens(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();if(ai.sourceDir!=null)paths.add(ai.sourceDir);if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        Set<String> found=new LinkedHashSet<>();int dex=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&found.size()<80){
                    ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long size=e.getSize();if(size<=0||size>90L*1024L*1024L)continue;
                    DexStrings d=new DexStrings(readAll(z.getInputStream(e)));d.collect(found,80);dex++;
                }
            }
        }
        AppState.get().log.add("NCM CONTROL7 TOKENS dex="+dex+" hits="+found.size());
        int n=0;for(String s:found){if(n++>=80)break;AppState.get().log.add("NCM CONTROL7 TOKEN "+shorten(s,430));}
    }

    private static final class DexStrings{
        final byte[] b;final int stringsN,stringsOff;
        DexStrings(byte[] x){b=x;stringsN=i32(0x38);stringsOff=i32(0x3c);}
        void collect(Set<String> out,int cap){
            if(b.length<0x70||stringsN<=0||stringsOff<=0)return;
            for(int i=0;i<stringsN&&out.size()<cap;i++){String s=str(i);if(interesting(s))out.add(s);}
        }
        String str(int idx){
            int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}
            int s=q[0],e=s,max=Math.min(b.length,s+1200);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;
            try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}
        }
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static boolean interesting(String s){
        if(s==null||s.length()<3||s.length()>500)return false;String x=s.toLowerCase(Locale.ROOT);
        boolean sing=s.contains("随心唱")||s.contains("人声音量")||x.contains("septrack")||x.contains("audioseptrack")||x.contains("musicvoice")||x.contains("voice_balance")||x.contains("septrackvoice");
        boolean cm=x.contains("cmapi")&&(x.contains("command")||x.contains("play")||x.contains("audio")||x.contains("voice")||x.contains("url")||x.contains("search")||x.contains("control"));
        boolean ucar=x.contains("ucar")&&(x.contains("action")||x.contains("aidl")||x.contains("control")||x.contains("audio")||x.contains("voice")||x.contains("media"));
        return sing||cm||ucar||s.equals(UCAR_ACTION);
    }

    private static synchronized void unbindAll(){
        if(app==null)return;
        for(ServiceConnection c:new ArrayList<>(CONNECTIONS)){try{app.unbindService(c);}catch(Throwable ignored){}CONNECTIONS.remove(c);}
    }

    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String safe(String s){return s==null?"":s;}
    private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
}
