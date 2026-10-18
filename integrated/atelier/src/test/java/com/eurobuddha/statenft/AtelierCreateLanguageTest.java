package com.eurobuddha.statenft;

import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierCreateLanguageTest {
    private MainActivity activity;private LinearLayout body;private NodeApi node;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jp1sAAAAASUVORK5CYII=";
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void set(String name,Object value)throws Exception{field(name).set(activity,value);}
    private Object invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render(String name)throws Exception{invoke(name,new Class<?>[]{});}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);
        for(String name:new String[]{"appbarBox","navBox"}){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);set(name,box);}
        set("content",new ScrollView(activity));node=mock(NodeApi.class);set("node",node);
    }
    @After public void cleanup(){Language.select(activity,"en");}
    private static List<EditText> edits(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText().toString()+" width="+t.getWidth()+" height="+t.getHeight()+" textHeight="+t.getLayout().getHeight()+" density="+t.getResources().getDisplayMetrics().density,t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
    @SuppressWarnings("unchecked") private List<String[]> pairs(String field)throws Exception{return(List<String[]>)field(field).get(activity);}
    private void nftDraft()throws Exception{
        set("nftName","Work 中文 Русский Українська");set("nftDesc","Description stays literal");set("nftOwner","Artist literal");set("nftEditions","12");set("nftImage",PNG);set("nftUrl","ipfs://fixture/image.png");set("nftExternal","");set("nftWeb","");set("traitTypeDraft","Mood");set("traitValueDraft","Calm");pairs("nftTraits").clear();pairs("nftTraits").add(new String[]{"District","Лівий берег"});
    }
    private void tokenDraft()throws Exception{
        set("tokName","Coin 中文");set("tokTicker","LIT");set("tokSupply","12345");set("tokDecimals","8");set("tokDesc","Description literal");set("tokIcon",PNG);set("tokUrl","");set("pairKeyDraft","website");set("pairValueDraft","https://example.invalid");pairs("tokPairs").clear();pairs("tokPairs").add(new String[]{"team","Команда 中文"});
    }
    @Test public void nftAndTokenFormsKeepEveryInputCursorImageAndMetadataAcrossLanguages()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            for(String kind:new String[]{"embed","hosted","token"}){
                boolean token=kind.equals("token");if(token)tokenDraft();else{nftDraft();set("nftEmbed",kind.equals("embed"));}
                render(token?"renderCreateToken":"renderCreateNft");List<EditText> inputs=edits(body);List<String> values=new ArrayList<>();
                for(EditText input:inputs){String v=input.getText().toString();values.add(v);input.setSelection(Math.min(2,v.length()));}
                String preview=(String)invoke(token?"previewTokenJson":"previewNftJson",new Class<?>[]{});
                for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.upper(Language.msg(token?l10n_nft_mint_mint_token:l10n_atelier_create_seal_nft)).toString()));assertEquals(preview,invoke(token?"previewTokenJson":"previewNftJson",new Class<?>[]{}));assertEquals(PNG,field(token?"tokIcon":"nftImage").get(activity));List<EditText> retained=edits(body);assertEquals(inputs.size(),retained.size());for(int i=0;i<inputs.size();i++){assertSame(inputs.get(i),retained.get(i));assertEquals(values.get(i),retained.get(i).getText().toString());assertEquals(Math.min(2,values.get(i).length()),retained.get(i).getSelectionStart());}}
            }
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    private void reject(String method,int message)throws Exception{invoke(method,new Class<?>[]{TextView.class},new TextView(activity));assertEquals(Language.text(activity,message),ShadowToast.getTextOfLatestToast());}
    @Test public void validationErrorsTranslateAndNeverReachTheNode()throws Exception{
        for(String code:CODES){Language.select(activity,code);
            set("nftName","");reject("mintNft",l10n_atelier_create_title_required);set("nftName","Valid");set("nftEditions","0");reject("mintNft",l10n_atelier_create_editions_range);set("nftEditions","1");set("nftEmbed",true);set("nftImage","");reject("mintNft",l10n_atelier_create_image_required);set("nftEmbed",false);set("nftUrl","bad url");reject("mintNft",l10n_atelier_create_image_url_required);set("nftUrl","ipfs://fixture/img");set("nftExternal","bad url");reject("mintNft",l10n_atelier_create_safe_urls);set("nftExternal","");
            set("tokName","");reject("mintToken",l10n_atelier_create_name_required);set("tokName","Valid");set("tokSupply","0");reject("mintToken",l10n_atelier_create_supply_required);set("tokSupply","100");set("tokDecimals","17");reject("mintToken",l10n_atelier_create_decimals_range);set("tokDecimals","0");set("tokUrl","bad url");reject("mintToken",l10n_atelier_create_safe_icon_url);
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void reservedCustomFieldsStayReservedInEveryLanguage()throws Exception{
        tokenDraft();set("pairKeyDraft","name");set("pairValueDraft","replacement");
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            render("renderCreateToken");TextView add=find(body,"+");int before=pairs("tokPairs").size();
            for(String code:CODES){Language.select(activity,code);add.performClick();assertEquals(Language.msg(l10n_atelier_create_reserved,"name").toString(),ShadowToast.getTextOfLatestToast());assertEquals(before,pairs("tokPairs").size());assertEquals("Coin 中文",field("tokName").get(activity));}
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void languageChangesDoNotAlterCapturedNftOrTokenCommands()throws Exception{
        List<String> commands=new ArrayList<>();String pk="0x"+"ab".repeat(32);
        doAnswer(call->{String command=call.getArgument(0);NodeApi.Cb cb=call.getArgument(1);if(command.equals("getaddress"))cb.onResult(new JSONObject().put("status",true).put("response",new JSONObject().put("publickey",pk)));else commands.add(command);return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        nftDraft();tokenDraft();set("nftEmbed",true);String firstNft=null,firstToken=null;
        for(String code:CODES){Language.select(activity,code);set("nftBusy",false);set("tokBusy",false);TextView nftButton=new TextView(activity),tokenButton=new TextView(activity);invoke("mintNft",new Class<?>[]{TextView.class},nftButton);invoke("mintToken",new Class<?>[]{TextView.class},tokenButton);assertEquals(Language.upper(Language.msg(l10n_atelier_create_minting)).toString(),nftButton.getText().toString());assertEquals(Language.upper(Language.msg(l10n_atelier_create_minting)).toString(),tokenButton.getText().toString());String nft=commands.get(commands.size()-2),token=commands.get(commands.size()-1);if(firstNft==null){firstNft=nft;firstToken=token;}else{assertEquals(firstNft,nft);assertEquals(firstToken,token);}assertTrue(nft.contains("Work 中文 Русский Українська"));assertTrue(nft.contains(" amount:12 decimals:0"));assertTrue(token.contains(" amount:12345 decimals:8"));assertTrue(token.contains("Команда 中文"));assertTrue(nft.endsWith(" signtoken:"+pk));assertTrue(token.endsWith(" signtoken:"+pk));}
        assertEquals(8,commands.size());
    }
}
