package com.eurobuddha.minimacore.chat;

import android.content.Context;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real disposable SQLite; no service, node, inbox ack or outgoing message. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ChatContactNameTest {
    static final String PEER="0x"+"a".repeat(64),OTHER="0x"+"b".repeat(64);
    Context context;ChatDb db;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();reset();context.deleteDatabase("keetchat.db");db=ChatDb.get(context);}
    @After public void close()throws Exception{reset();context.deleteDatabase("keetchat.db");}
    void reset()throws Exception{Field f=ChatDb.class.getDeclaredField("instance");f.setAccessible(true);ChatDb old=(ChatDb)f.get(null);if(old!=null)old.close();f.set(null,null);}
    void message(String peer,String id,String name,boolean incoming){ChatDb.Msg m=new ChatDb.Msg();m.pubkey=peer;m.msgid=id;m.name=name;m.body="public test";m.incoming=incoming;m.ts=100;assertTrue(db.addMessage(m));}
    void upgradeFrom(int version)throws Exception{db.getWritableDatabase().setVersion(version);reset();db=ChatDb.get(context);db.getWritableDatabase();}
    @Test public void unnamedSenderAdoptsNameFromALaterMessage(){
        db.ensureContact(PEER,"");assertEquals(PEER,db.contactName(PEER));
        db.ensureContact(PEER,"Alice");assertEquals("Alice",db.contactName(PEER));assertEquals("Alice",db.threads().get(0).name);assertEquals("Alice",db.contactList().get(0).name);
    }
    @Test public void customNamesPaymentAddressesAndReadReceiptsArePreserved(){
        db.putContact(PEER,"My friend");db.setContactPayAddr(PEER,"Mx123");db.setLastReceipt(PEER,"receipt");
        db.ensureContact(PEER,"Alice");assertEquals("My friend",db.contactName(PEER));assertEquals("Mx123",db.contactPayAddr(PEER));assertEquals("receipt",db.lastReceipt(PEER));
    }
    @Test public void blankWireNamesCannotReplaceNamesOrBecomeWhitespaceContacts(){
        db.ensureContact(PEER,"  ");assertEquals(PEER,db.contactName(PEER));
        db.ensureContact(PEER," Alice ");db.ensureContact(PEER,"");assertEquals("Alice",db.contactName(PEER));
    }
    @Test public void migrationRecoversAlreadyReceivedNameWithoutWaitingForAnotherMessage()throws Exception {
        db.ensureContact(PEER,"");message(PEER,"one","Old name",true);message(PEER,"two","Alice",true);
        message(PEER,"three","My outgoing name",false);message(OTHER,"four","Other peer",true);
        db.setContactPayAddr(PEER,"Mx123");db.setLastReceipt(PEER,"receipt");upgradeFrom(2);
        assertEquals("Alice",db.contactName(PEER));assertEquals(3,db.thread(PEER).size());assertEquals("Mx123",db.contactPayAddr(PEER));assertEquals("receipt",db.lastReceipt(PEER));
        assertFalse(db.hasContact(OTHER));
    }
    @Test public void migrationDoesNotOverwriteNamedContactsAndSurvivesReopen()throws Exception {
        db.putContact(PEER,"My label");message(PEER,"one","Alice",true);upgradeFrom(2);
        assertEquals("My label",db.contactName(PEER));reset();db=ChatDb.get(context);assertEquals("My label",db.contactName(PEER));
    }
    @Test public void legacyUpgradePreservesPlaceholderWithoutIncomingName()throws Exception {
        db.ensureContact(PEER,"");message(PEER,"one","",true);message(PEER,"two","My name",false);upgradeFrom(1);
        assertEquals(PEER,db.contactName(PEER));assertEquals(2,db.thread(PEER).size());
    }
    @Test public void laterAutomaticNameDoesNotReplaceAnEstablishedContactName(){
        db.ensureContact(PEER,"Alice");db.ensureContact(PEER,"Different name");assertEquals("Alice",db.contactName(PEER));
    }
    @Test public void listDoesNotMistakeHexPrefixedNicknameForAnUnnamedContact()throws Exception {
        ChatView view=org.mockito.Mockito.mock(ChatView.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        ChatDb.Thread thread=new ChatDb.Thread();thread.pubkey=PEER;thread.name="0xAlice";
        java.lang.reflect.Method display=ChatView.class.getDeclaredMethod("displayName",ChatDb.Thread.class);
        display.setAccessible(true);assertEquals("0xAlice",display.invoke(view,thread));
    }

}
