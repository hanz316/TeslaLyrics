package com.hanz.commanderlogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Read-oriented protocol reconstructed from the user's Commander WeChat Mini Program. */
final class CommanderProtocol {
    static final int A0 = 0xA0, A8 = 0xA8, B0 = 0xB0, C0 = 0xC0, D0 = 0xD0, D1 = 0xD1, D2 = 0xD2;

    static byte[] frame(int type, byte[] payload) {
        if (payload == null) payload = new byte[0];
        int n = payload.length;
        byte[] out = new byte[n + 6];
        out[0] = 0x55; out[1] = 0x7F; out[2] = (byte) type;
        out[3] = (byte) ((n >>> 8) & 0xFF); out[4] = (byte) (n & 0xFF);
        int sum = type + (out[3] & 0xFF) + (out[4] & 0xFF);
        for (int i=0;i<n;i++) { out[5+i] = payload[i]; sum += payload[i] & 0xFF; }
        out[out.length-1] = (byte) sum;
        return out;
    }

    static byte[] cmdA0() { return frame(A0, new byte[0]); }
    static byte[] cmdA8(String key) {
        if (key == null || key.length()!=4) return null;
        byte[] b = key.getBytes(StandardCharsets.US_ASCII);
        return b.length==4 ? frame(A8,b) : null;
    }
    static byte[] cmdB0(boolean on) { return frame(B0,new byte[]{(byte)(on?1:0)}); }
    static byte[] cmdD0(int mode) { return frame(D0,new byte[]{(byte)mode}); }
    static byte[] cmdC0AllRx() { return frame(C0,new byte[]{1}); }
    static byte[] cmdC0Pause() { return frame(C0,new byte[]{2}); }
    static byte[] cmdC0Off() { return frame(C0,new byte[]{0}); }

    static final class Packet {
        final int type; final byte[] payload;
        Packet(int type, byte[] payload) { this.type=type; this.payload=payload; }
    }

    static final class StreamParser {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long lastFeedMs;
        List<Packet> feed(byte[] bytes, long nowMs) {
            if (lastFeedMs != 0 && nowMs-lastFeedMs >= 500) buf.reset();
            lastFeedMs = nowMs;
            try { buf.write(bytes); } catch (Exception ignored) {}
            byte[] all = buf.toByteArray();
            ArrayList<Packet> out = new ArrayList<>();
            int p=0;
            while (true) {
                while (p+1<all.length && !((all[p]&0xFF)==0x55 && (all[p+1]&0xFF)==0x7F)) p++;
                if (p+6>all.length) break;
                int type=all[p+2]&0xFF;
                int len=((all[p+3]&0xFF)<<8)|(all[p+4]&0xFF);
                if (len>4096) { p++; continue; }
                int total=6+len;
                if (p+total>all.length) break;
                int sum=type+(all[p+3]&0xFF)+(all[p+4]&0xFF);
                for(int i=0;i<len;i++) sum += all[p+5+i]&0xFF;
                int chk=all[p+5+len]&0xFF;
                if ((sum&0xFF)==chk) out.add(new Packet(type, Arrays.copyOfRange(all,p+5,p+5+len)));
                p += total;
            }
            buf.reset();
            if (p<all.length) try { buf.write(all,p,all.length-p); } catch(Exception ignored) {}
            return out;
        }
        void reset(){buf.reset();lastFeedMs=0;}
    }

    static int u16le(byte[] p,int o){ return (p[o]&255)|((p[o+1]&255)<<8); }
    static long u32le(byte[] p,int o){ return (p[o]&255L)|((p[o+1]&255L)<<8)|((p[o+2]&255L)<<16)|((p[o+3]&255L)<<24); }
    static int sign11(int v){ return (v&0x400)!=0?v-0x800:v; }
    static int sign14(int v){ return (v&0x2000)!=0?v-0x4000:v; }
    static String hex(byte[] b){ StringBuilder s=new StringBuilder(); for(byte v:b)s.append(String.format(Locale.US,"%02X",v&255)); return s.toString(); }

    static String deviceInfo(byte[] p){
        if(p.length<18)return "A0 len="+p.length;
        String uid=hex(Arrays.copyOfRange(p,4,16));
        StringBuilder mac=new StringBuilder(); for(int i=17;i>=12;i--){if(mac.length()>0)mac.append(':');mac.append(String.format(Locale.US,"%02X",p[i]&255));}
        String vin="";
        if(p.length==178 && (p[46]&255)!=255) vin=new String(p,46,17,StandardCharsets.US_ASCII);
        else if(p.length>=58 && (p[40]&255)==1) vin=new String(p,41,17,StandardCharsets.US_ASCII);
        return "hwRaw="+(p[0]&255)+" uid="+uid+" mac="+mac+" vin="+vin+" btBase="+((p[1]&255)<<8)+" fwBase="+((p[2]&255)<<8);
    }

    static String gauge(byte[] p){
        if(p.length<16)return "B0 len="+p.length;
        long w0=u32le(p,0), w1=u32le(p,4), w2=u32le(p,12);
        int speed=(int)(w0&0x1FF), gear=(int)((w0>>>9)&7), turn=(int)((w0>>>12)&3), ap=(int)((w0>>>14)&3), doors=(int)((w0>>>16)&15), soc=(int)((w0>>>24)&127);
        int light=(int)((w0>>>20)&15)|((int)((w0>>>31)&1)<<4); if(((p[4]>>>4)&1)!=0)light|=32;
        double odo=((w1>>>6)&0x03FFFFFF)/10.0;
        int accel=(int)Math.min(100,Math.round((w2&255)*100.0/250.0));
        double rear=Math.round(sign11((int)((w2>>>8)&0x7FF))/2.0), front=Math.round(sign11((int)((w2>>>19)&0x7FF))/2.0);
        String gearText=gear==1?"P":gear==2?"R":gear==3?"N":gear==4?"D":"?";
        StringBuilder s=new StringBuilder(String.format(Locale.US,"speed=%d gear=%s soc=%d%% odo=%.1fkm accel=%d%% front=%.1fkW rear=%.1fkW turn=%d ap=%d doors=0x%X lights=0x%X",speed,gearText,soc,odo,accel,front,rear,turn,ap,doors,light));
        s.append(String.format(Locale.US," tires=[%.3f %.3f %.3f %.3f]bar",(p[8]&255)*.025,(p[9]&255)*.025,(p[10]&255)*.025,(p[11]&255)*.025));
        if(p.length>=33){
            int w3=(p[15]&255)|((p[16]&255)<<8)|((p[17]&255)<<16); int alt=sign14((w3>>>6)&0x3FFF); int heat=(p[17]>>>4)&3; boolean kmh=((p[17]>>>6)&1)!=0; boolean hands=((p[17]>>>7)&1)!=0;
            long br=(p[18]&255L)|((p[19]&255L)<<8)|((p[20]&255L)<<16)|((p[21]&255L)<<24)|((p[22]&255L)<<32);
            int fl=(int)(br&1023)-40,fr=(int)((br>>>10)&1023)-40,rl=(int)((br>>>20)&1023)-40,rr=(int)((br>>>30)&1023)-40;
            long hv=u32le(p,23); int blower=(int)(hv&1023)*5, hvac=(int)((hv>>>10)&2047)*5; double cabin=((hv>>>21)&2047)*.1-40,ambient=(p[27]&255)*.5-40;
            long b=u32le(p,28); double cell=(b&4095)*.002, range=((b>>>12)&1023)*1.61, bt=((b>>>22)&511)*.5-40; int tail=u16le(p,31),limit=5*((tail>>>7)&31),blindL=(tail>>>12)&3,blindR=(tail>>>14)&3;
            s.append(String.format(Locale.US," alt=%dm heat=%d unit=%s hands=%s brakeTemp=[%d %d %d %d]C blower=%drpm hvac=%dW cabin=%.1fC ambient=%.1fC cell=%.3fV range=%.1fkm batt=%.1fC limit=%d blind=%d/%d",alt,heat,kmh?"KMH":"MPH",hands,fl,fr,rl,rr,blower,hvac,cabin,ambient,cell,range,bt,limit,blindL,blindR));
        }
        return s.toString();
    }

    static String battery(byte[] p){
        if(p.length<29)return "D0 len="+p.length;
        double v=u16le(p,0)*.01; short si=(short)u16le(p,2); double a=-si*.1,kw=v*a/1000.0;
        double dis=u32le(p,4)*.001,ch=u32le(p,8)*.001,rem=u16le(p,12)*.02,full=u16le(p,14)*.02,reserve=u16le(p,16)*.01;
        int cells=(p[18]&255)|((p[19]&255)<<8)|((p[20]&255)<<16);double max=(cells&4095)*.002,min=((cells>>>12)&4095)*.002;
        long w=u32le(p,21);double cap=(w&1023)*.1,range=((w>>>10)&1023)*1.61,temp=((w>>>22)&511)*.5-40;int heat=(int)((w>>>20)&3);double actual=full>0?rem/full*100:0;
        return String.format(Locale.US,"V=%.2fV I=%.1fA P=%.2fkW charged=%.3fkWh discharged=%.3fkWh rem=%.2fkWh full=%.2fkWh reserve=%.2fkWh actualSOC=%.1f%% cell=%.3f/%.3fV delta=%.0fmV factory=%.1fkWh range=%.1fkm temp=%.1fC heat=%d",v,a,kw,ch,dis,rem,full,reserve,actual,max,min,(max-min)*1000,cap,range,temp,heat);
    }

    static String cells(byte[] p){ if(p.length<8)return "D1 len="+p.length; int g=p[0]&255; return String.format(Locale.US,"group=%d cells[%d..%d]=%.4f %.4f %.4fV status=%d",g,g*3,g*3+2,u16le(p,2)*.0001,u16le(p,4)*.0001,u16le(p,6)*.0001,p[1]&255); }
    static String dcdc(byte[] p){ if(p.length<8)return "D2 len="+p.length; double out=u16le(p,0)*.01,in=u16le(p,2)*.1,a=u16le(p,4)*.1; return String.format(Locale.US,"in=%.1fV out=%.2fV I=%.1fA P=%.1fW rawHi=0x%04X",in,out,a,out*a,u16le(p,6)); }
    static String can(byte[] p){ if(p.length<2)return "C0 len="+p.length; int id=((p[0]&255)<<8)|(p[1]&255); return String.format(Locale.US,"id=0x%03X data=%s",id,hex(Arrays.copyOfRange(p,2,p.length))); }
}
