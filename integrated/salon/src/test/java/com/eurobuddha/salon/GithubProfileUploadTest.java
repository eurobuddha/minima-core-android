package com.eurobuddha.salon;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GithubProfileUploadTest {
    private Hosting.Profile profile; private GithubUploader uploader;
    private MockedStatic<Hosting> hosting; private MockedStatic<Crypt> crypt;
    private final List<Response> seen=new ArrayList<>(); private final Deque<Response> replies=new ArrayDeque<>();
    private static final String PATH="owner/profile.json", SHA="a".repeat(40);
    /** Reuses HostingVerificationTest's connection fixture and tracks both streams. */
    private static class Response extends HttpURLConnection {
        final int status; final byte[] data; boolean disconnected,inputClosed,outputClosed,failStatus,failRead,failWrite;
        final ByteArrayOutputStream output=new ByteArrayOutputStream(){@Override public void close(){outputClosed=true;}};
        Response(int status,String body)throws Exception{super(new URL("https://api.github.com/"));this.status=status;data=body.getBytes(StandardCharsets.UTF_8);}
        void target(String target)throws Exception{url=new URL(target);}
        @Override public int getResponseCode()throws IOException{if(failStatus)throw new IOException("fixture-secret");return status;}
        @Override public InputStream getInputStream(){return new FilterInputStream(new ByteArrayInputStream(data)){
            @Override public int read(byte[] b,int o,int n)throws IOException{if(failRead)throw new IOException("fixture-secret");return super.read(b,o,n);}
            @Override public void close()throws IOException{inputClosed=true;super.close();}
        };}
        @Override public OutputStream getOutputStream()throws IOException{if(failWrite)throw new IOException("fixture-secret");return output;}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        JSONObject payload()throws Exception{return new JSONObject(output.toString("UTF-8"));}
    }
    private String metadata(String path,String sha)throws Exception{return new JSONObject().put("type","file").put("path",path).put("sha",sha).toString();}
    private Response reply(int code,String body)throws Exception{Response r=new Response(code,body);replies.add(r);return r;}
    private String save(String path)throws Exception{return uploader.putProfileFile("Literal 中文 Русский Українська".getBytes(StandardCharsets.UTF_8),path,"application/json");}
    @Before public void setup()throws Exception{
        profile=Hosting.Profile.fresh(Hosting.TYPE_GITHUB);Hosting.put(profile.cfg(),"owner","fixture-owner");Hosting.put(profile.cfg(),"repo","salon");Hosting.put(profile.cfg(),"branch","main");Hosting.put(profile.cfg(),"token","wrapped-fixture");
        uploader=new GithubUploader(profile);crypt=mockStatic(Crypt.class);crypt.when(()->Crypt.decrypt("wrapped-fixture")).thenReturn("fixture-secret");
        hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS);
        hosting.when(()->Hosting.open(anyString(),anyString())).thenAnswer(c->{assertFalse("unexpected network request",replies.isEmpty());Response response=replies.remove();response.target(c.getArgument(0));response.setRequestMethod(c.getArgument(1));response.setConnectTimeout(10000);response.setReadTimeout(30000);seen.add(response);return response;});
    }
    @After public void cleanup(){hosting.close();crypt.close();Thread.interrupted();}
    @Test public void repeatedSaveReadsFreshShaAndKeepsTheSamePublicUrl()throws Exception{
        for(String sha:new String[]{SHA,"b".repeat(40)}){
            Response get=reply(200,metadata(PATH,sha)),put=reply(200,"");
            assertEquals("https://raw.githubusercontent.com/fixture-owner/salon/main/"+PATH,save(PATH));
            assertEquals("GET",get.getRequestMethod());assertTrue(get.getURL().toString().endsWith(PATH+"?ref=main"));
            assertEquals("PUT",put.getRequestMethod());assertEquals(sha,put.payload().getString("sha"));assertEquals("main",put.payload().getString("branch"));
            assertEquals("Literal 中文 Русский Українська",new String(Base64.getDecoder().decode(put.payload().getString("content")),StandardCharsets.UTF_8));
            assertTrue(get.inputClosed);assertTrue(put.outputClosed);assertTrue(get.disconnected);assertTrue(put.disconnected);
            for(Response con:Arrays.asList(get,put)){assertFalse(con.getInstanceFollowRedirects());assertEquals("Bearer fixture-secret",con.getRequestProperty("Authorization"));assertEquals("2022-11-28",con.getRequestProperty("X-GitHub-Api-Version"));}
            assertEquals("application/vnd.github.object+json",get.getRequestProperty("Accept"));assertFalse(get.getUseCaches());
        }
        assertEquals(4,seen.size());
    }
    @Test public void onlyNotFoundAllowsCreationWithoutASha()throws Exception{
        reply(404,"");Response put=reply(201,"");save(PATH);assertFalse(put.payload().has("sha"));
        for(int status:new int[]{301,302,304,401,403,429,500}){
            int count=seen.size();Response get=reply(status,"");GithubUploader.ProfileFailure error=assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertEquals(GithubUploader.ProfileError.READ,error.reason);assertEquals(status,error.status);assertEquals(count+1,seen.size());assertTrue(get.disconnected);
        }
    }
    @Test public void branchIsEncodedInLookupAndUnchangedInUpdateBody()throws Exception{
        String branch="release/中文&draft";Hosting.put(profile.cfg(),"branch",branch);Response get=reply(200,metadata(PATH,SHA)),put=reply(200,"");save(PATH);
        assertTrue(get.getURL().toString().endsWith("?ref="+URLEncoder.encode(branch,"UTF-8")));assertEquals(branch,put.payload().getString("branch"));
    }
    @Test public void badMetadataNeverTriggersAnUpdate()throws Exception{
        for(String body:new String[]{"not json","[]","{}",metadata("wrong/path",SHA),metadata(PATH,"bad"),new JSONObject(metadata(PATH,SHA)).put("type","dir").toString(),new JSONObject(metadata(PATH,SHA)).put("type","symlink").toString()," ".repeat(2*1024*1024+1)}){
            int count=seen.size();Response get=reply(200,body);GithubUploader.ProfileFailure error=assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertEquals(GithubUploader.ProfileError.INVALID,error.reason);assertEquals(count+1,seen.size());assertTrue(get.disconnected);assertTrue(get.inputClosed);
        }
    }
    @Test public void staleShaOrValidationFailureDoesNotRetryOrClaimSuccess()throws Exception{
        for(int status:new int[]{409,422}){
            int count=seen.size();reply(200,metadata(PATH,SHA));Response put=reply(status,"");GithubUploader.ProfileFailure error=assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertEquals(GithubUploader.ProfileError.CONFLICT,error.reason);assertEquals(status,error.status);assertEquals(count+2,seen.size());assertTrue(put.disconnected);assertTrue(put.outputClosed);
        }
    }
    @Test public void unexpectedWriteStatusesIncludingRedirectsAreUnconfirmed()throws Exception{
        for(int status:new int[]{202,204,301,302,400,401,403,500}){
            reply(200,metadata(PATH,SHA));Response put=reply(status,"");GithubUploader.ProfileFailure error=assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertEquals(GithubUploader.ProfileError.WRITE,error.reason);assertTrue(put.disconnected);assertFalse(put.getInstanceFollowRedirects());
        }
    }
    @Test public void transportFailuresCloseResourcesAndDoNotExposeCredentials()throws Exception{
        for(int failure=0;failure<4;failure++){
            Response get=reply(200,metadata(PATH,SHA)),put=null;
            if(failure==0)get.failStatus=true;
            if(failure==1)get.failRead=true;
            if(failure>=2){put=reply(200,"");if(failure==2)put.failWrite=true;else put.failStatus=true;}
            GithubUploader.ProfileFailure error=assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertEquals(GithubUploader.ProfileError.UNAVAILABLE,error.reason);assertFalse(error.getMessage().contains("fixture-secret"));assertTrue(get.disconnected);if(failure!=0)assertTrue(get.inputClosed);if(put!=null)assertTrue(put.disconnected);
        }
    }
    @Test public void interruptionStopsBeforeAnyNetworkWorkAndRetainsFlag(){
        Thread.currentThread().interrupt();assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertTrue(Thread.currentThread().isInterrupted());assertTrue(seen.isEmpty());
    }
    @Test public void optInCannotReplaceMediaOtherFoldersOrInjectedPaths()throws Exception{
        for(String path:new String[]{"owner/media/photo.png","../profile.json","owner/../index.html","owner/profile.json?x=1","owner/profile.json#x","owner/sub/profile.json","owner/other.json",null})assertThrows(GithubUploader.ProfileFailure.class,()->save(path));
        Hosting.put(profile.cfg(),"owner","bad/owner");assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));Hosting.put(profile.cfg(),"owner","fixture-owner");Hosting.put(profile.cfg(),"repo","..");assertThrows(GithubUploader.ProfileFailure.class,()->save(PATH));assertTrue(seen.isEmpty());
    }
    @Test public void ordinaryMediaUploadStillOmitsShaAndRefusesOverwrite()throws Exception{
        Response put=reply(422,"");assertThrows(Hosting.HostingException.class,()->uploader.putFile(new byte[]{1,2},"owner/media.jpg","image/jpeg"));assertEquals(1,seen.size());assertEquals("PUT",put.getRequestMethod());assertFalse(put.payload().has("sha"));assertTrue(put.disconnected);
    }
    @Test public void realProfileOrchestratorUpdatesBothFilesOnEachPublication()throws Exception{
        hosting.when(()->Hosting.forProfile(profile)).thenReturn(uploader);hosting.when(()->Hosting.verifyUrl(anyString(),eq(profile))).thenAnswer(c->null);
        String expected="https://raw.githubusercontent.com/fixture-owner/salon/main/"+PATH;
        for(int run=0;run<2;run++){
            for(String file:new String[]{"index.html","profile.json"}){reply(run==0?404:200,metadata("owner/"+file,SHA));reply(run==0?201:200,"");}
            assertEquals(expected,ProfileHosting.upload(profile,"owner","JSON "+run,"HTML "+run));
        }
        assertEquals(8,seen.size());assertEquals("HTML 1",new String(Base64.getDecoder().decode(seen.get(5).payload().getString("content")),StandardCharsets.UTF_8));assertEquals("JSON 1",new String(Base64.getDecoder().decode(seen.get(7).payload().getString("content")),StandardCharsets.UTF_8));
        hosting.verify(()->Hosting.verifyUrl(expected,profile),times(2));
    }
}
