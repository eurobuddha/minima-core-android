package com.eurobuddha.salon;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostingVerificationTest {
    static class Response extends HttpURLConnection {
        int code; String location; boolean disconnected, closed; int reads;
        boolean failStatus, failBody, failRead;
        byte[] bytes;
        Response(URL url,int code,String location,byte[] bytes){super(url);this.code=code;this.location=location;this.bytes=bytes;}
        @Override public int getResponseCode()throws IOException{if(failStatus)throw new IOException("status unavailable");return code;}
        @Override public String getHeaderField(String key){assertEquals("Location",key);return location;}
        @Override public InputStream getInputStream()throws IOException{
            if(failBody)throw new IOException("body unavailable");
            return new InputStream(){
                @Override public int read()throws IOException{reads++;if(failRead)throw new IOException("body disconnected");return reads<=bytes.length?bytes[reads-1]&255:-1;}
                @Override public void close(){closed=true;}
            };
        }
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
    }
    static Response response(URL u,int code,String location){return new Response(u,code,location,new byte[]{1,2,3});}
    interface Check {void run()throws Exception;}
    static void refused(Check check)throws Exception{try{check.run();fail("verification should fail");}catch(Hosting.HostingException expected){assertNotNull(expected.getMessage());}}
    static Hosting.Profile own(String host){Hosting.Profile p=Hosting.Profile.fresh(Hosting.TYPE_KUBO);Hosting.put(p.cfg(),"gateway",host);return p;}
    @Test public void resolvesRelativeRedirectAndReadsOnlyTheFinalPrefix()throws Exception{
        List<Response> seen=new ArrayList<>();
        Hosting.verifyOnce("https://8.8.8.8/start",null,u->{Response r=response(u,seen.isEmpty()?302:206,"/image");seen.add(r);return r;});
        assertEquals(2,seen.size());assertEquals("https://8.8.8.8/image",seen.get(1).getURL().toString());
        for(Response r:seen){assertTrue(r.disconnected);assertFalse(r.getInstanceFollowRedirects());assertEquals("bytes=0-1",r.getRequestProperty("Range"));assertEquals(8000,r.getConnectTimeout());assertEquals(15000,r.getReadTimeout());}
        assertEquals(0,seen.get(0).reads);assertEquals(1,seen.get(1).reads);assertTrue(seen.get(1).closed);
    }
    @Test public void redirectToUnconfiguredPrivateHostNeverConnects()throws Exception{
        for(String host:new String[]{"127.0.0.1","192.168.1.1","169.254.169.254","100.64.0.1","[fc00::1]"}){
            List<Response> seen=new ArrayList<>();
            refused(()->Hosting.verifyOnce("https://8.8.8.8/start",null,u->{Response r=response(u,302,"http://"+host+"/command");seen.add(r);return r;}));
            assertEquals(host,1,seen.size());assertTrue(seen.get(0).disconnected);
        }
    }
    @Test public void explicitlyConfiguredLanGatewayStillWorksAcrossRedirects()throws Exception{
        Hosting.Profile p=own("http://192.168.1.10:8080");List<Response> seen=new ArrayList<>();
        Hosting.verifyOnce("https://8.8.8.8/start",p,u->{Response r=response(u,seen.isEmpty()?307:200,"http://192.168.1.10:8080/file");seen.add(r);return r;});
        assertEquals(2,seen.size());assertEquals("192.168.1.10",seen.get(1).getURL().getHost());assertTrue(seen.get(1).closed);
    }
    @Test public void configuredHostComparisonIsCaseInsensitiveButExact()throws Exception{
        Hosting.Profile p=own("http://Gateway.INTERNAL:8080");
        Hosting.verifyOnce("http://gateway.internal/file",p,u->response(u,200,null));
        refused(()->Hosting.verifyOnce("http://127.0.0.1/file",p,u->{fail("unconfigured host opened");return null;}));
    }
    @Test public void initialPrivateHostAndForeignSchemesAreRefusedBeforeOpening()throws Exception{
        for(String url:new String[]{"http://127.0.0.1/","file:///private/file","ftp://8.8.8.8/file"})
            refused(()->Hosting.verifyOnce(url,null,u->{fail("refused URL opened");return null;}));
        refused(()->Hosting.verifyOnce("file:///private/file",own("file:///private/file"),u->{fail();return null;}));
    }
    @Test public void redirectSchemeIsCheckedEvenWithConfiguredHost()throws Exception{
        List<Response> seen=new ArrayList<>();
        refused(()->Hosting.verifyOnce("https://8.8.8.8/start",own("8.8.8.8"),u->{Response r=response(u,302,"ftp://8.8.8.8/file");seen.add(r);return r;}));
        assertEquals(1,seen.size());assertTrue(seen.get(0).disconnected);
    }
    @Test public void redirectToErrorAndRedirectWithoutLocationDoNotCountAsSuccess()throws Exception{
        List<Response> seen=new ArrayList<>();
        refused(()->Hosting.verifyOnce("https://8.8.8.8/start",null,u->{Response r=response(u,seen.isEmpty()?308:404,"/gone");seen.add(r);return r;}));
        assertEquals(2,seen.size());assertTrue(seen.get(1).disconnected);assertEquals(0,seen.get(1).reads);
        for(String location:new String[]{null,""})refused(()->Hosting.verifyOnce("https://8.8.8.8/start",null,u->response(u,302,location)));
    }
    @Test public void loopsStopAfterThreeRedirectsAndDisconnectEveryAttempt()throws Exception{
        List<Response> seen=new ArrayList<>();
        refused(()->Hosting.verifyOnce("https://8.8.8.8/start",null,u->{Response r=response(u,301,"/start");seen.add(r);return r;}));
        assertEquals(4,seen.size());for(Response r:seen)assertTrue(r.disconnected);
    }
    @Test public void onlyReadableOkOrPartialContentResponsesPass()throws Exception{
        for(int code:new int[]{200,206})Hosting.verifyOnce("https://8.8.8.8/file",null,u->response(u,code,null));
        for(int code:new int[]{201,202,204,205,300,304,305,400,401,403,404,500})
            refused(()->Hosting.verifyOnce("https://8.8.8.8/file",null,u->response(u,code,null)));
    }
    @Test public void emptyAndBrokenBodiesAndStatusFailuresAlwaysDisconnect()throws Exception{
        for(int failure=0;failure<4;failure++){
            Response r=response(new URL("https://8.8.8.8/file"),200,null);
            if(failure==0)r.bytes=new byte[0];if(failure==1)r.failStatus=true;if(failure==2)r.failBody=true;if(failure==3)r.failRead=true;
            refused(()->Hosting.verifyOnce(r.getURL().toString(),null,u->r));assertTrue(r.disconnected);
            if(failure==0||failure==3)assertTrue(r.closed);
        }
    }
    @Test public void preexistingInterruptionDoesNotOpenOrRetry()throws Exception{
        Thread.currentThread().interrupt();
        try{
            refused(()->Hosting.verifyOnce("https://8.8.8.8/file",null,u->{fail("interrupted probe opened");return null;}));
            refused(()->Hosting.verifyUrl("file:///private/file",Hosting.Profile.fresh(Hosting.TYPE_GITHUB)));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test public void interruptionDuringRetrySleepStopsWithoutClearingFlag()throws Exception{
        AtomicBoolean failed=new AtomicBoolean(),interrupted=new AtomicBoolean();
        Thread worker=new Thread(()->{
            try{Hosting.verifyUrl("file:///private/file",Hosting.Profile.fresh(Hosting.TYPE_GITHUB));}
            catch(Hosting.HostingException expected){failed.set(true);interrupted.set(Thread.currentThread().isInterrupted());}
        });worker.start();
        try{
            long end=System.nanoTime()+2_000_000_000L;
            while(worker.isAlive()&&worker.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(Thread.State.TIMED_WAITING,worker.getState());worker.interrupt();worker.join(1000);
            assertFalse(worker.isAlive());assertTrue(failed.get());assertTrue(interrupted.get());
        }finally{worker.interrupt();worker.join(1000);}
    }
}
