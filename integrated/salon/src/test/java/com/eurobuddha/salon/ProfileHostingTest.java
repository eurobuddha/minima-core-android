package com.eurobuddha.salon;

import java.util.*;
import org.junit.*;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ProfileHostingTest {
    private MockedStatic<Hosting> hosting;
    private Hosting.Uploader uploader;
    @Before public void setup()throws Exception {
        uploader=mock(Hosting.Uploader.class);hosting=mockStatic(Hosting.class);
        hosting.when(()->Hosting.forProfile(any())).thenReturn(uploader);
        hosting.when(()->Hosting.slug(anyString())).thenCallRealMethod();
        hosting.when(()->Hosting.put(any(org.json.JSONObject.class),anyString(),any())).thenAnswer(c->{c.callRealMethod();return null;});
        when(uploader.putFile(any(),anyString(),anyString())).thenAnswer(c->"https://example.invalid/"+c.getArgument(1));
    }
    @After public void cleanup(){hosting.close();}
    @Test public void mutableHostChecksRendererBeforeReplacingStableProfile()throws Exception {
        Hosting.Profile host=Hosting.Profile.fresh(Hosting.TYPE_SFTP);
        assertEquals("https://example.invalid/owner/profile.json",ProfileHosting.upload(host,"owner","literal JSON","literal HTML"));
        InOrder order=inOrder(uploader);order.verify(uploader).putFile(any(),eq("owner/index.html"),eq("text/html"));order.verify(uploader).putFile(any(),eq("owner/profile.json"),eq("application/json"));order.verify(uploader).close();
        hosting.verify(()->Hosting.verifyUrl("https://example.invalid/owner/index.html",host));hosting.verify(()->Hosting.verifyUrl("https://example.invalid/owner/profile.json",host));
    }
    @Test public void rendererFailureCannotReportSuccessOrOverwriteProfile()throws Exception {
        when(uploader.putFile(any(),endsWith("index.html"),anyString())).thenThrow(new Hosting.HostingException("renderer failed"));
        assertThrows(Hosting.HostingException.class,()->ProfileHosting.upload(Hosting.Profile.fresh(Hosting.TYPE_WEBDAV),"owner","JSON","HTML"));
        verify(uploader,never()).putFile(any(),endsWith("profile.json"),anyString());verify(uploader).close();
    }
    @Test public void rendererProbeFailureCannotOverwriteProfile()throws Exception {
        Hosting.Profile host=Hosting.Profile.fresh(Hosting.TYPE_WEBDAV);
        hosting.when(()->Hosting.verifyUrl(endsWith("index.html"),eq(host))).thenThrow(new Hosting.HostingException("probe failed"));
        assertThrows(Hosting.HostingException.class,()->ProfileHosting.upload(host,"owner","JSON","HTML"));
        verify(uploader,never()).putFile(any(),endsWith("profile.json"),anyString());verify(uploader).close();
    }
    @Test public void ipfsPublishesOneDirectoryWithRelativeSiblings()throws Exception {
        for(String type:new String[]{Hosting.TYPE_KUBO,Hosting.TYPE_PINATA}) {
            Hosting.Profile host=Hosting.Profile.fresh(type);
            Hosting.Uploader directory=mock(Hosting.Uploader.class,withSettings().extraInterfaces(Hosting.DirUploader.class));
            when(((Hosting.DirUploader)directory).putDirectory(anyList(),isNull())).thenAnswer(c->{List<Hosting.Entry> files=c.getArgument(0);assertEquals(2,files.size());assertEquals("profile.json",files.get(0).relPath);assertEquals("index.html",files.get(1).relPath);assertArrayEquals("JSON".getBytes(),files.get(0).bytes);assertArrayEquals("HTML".getBytes(),files.get(1).bytes);return "https://example.invalid/ipfs/cid/";});
            hosting.when(()->Hosting.forProfile(host)).thenReturn(directory);
            assertEquals("https://example.invalid/ipfs/cid/profile.json",ProfileHosting.upload(host,"owner","JSON","HTML"));
            verify(directory,never()).putFile(any(),anyString(),anyString());verify(directory).close();
            hosting.verify(()->Hosting.verifyUrl("https://example.invalid/ipfs/cid/index.html",host));
        }
    }
    @Test public void meshAndBlossomUploadOnlyJson()throws Exception {
        for(String type:new String[]{Hosting.TYPE_MAXIMA,Hosting.TYPE_BLOSSOM})ProfileHosting.upload(Hosting.Profile.fresh(type),"owner","JSON","HTML");
        verify(uploader,times(2)).putFile(any(),eq("owner/profile.json"),eq("application/json"));verify(uploader,never()).putFile(any(),anyString(),eq("text/html"));verify(uploader,times(2)).close();
    }
    @Test public void emptyReferenceFailsAndClosesUploader()throws Exception {
        when(uploader.putFile(any(),anyString(),anyString())).thenReturn(" ");
        assertThrows(Hosting.HostingException.class,()->ProfileHosting.upload(Hosting.Profile.fresh(Hosting.TYPE_BLOSSOM),"owner","JSON","HTML"));verify(uploader).close();
    }
}
