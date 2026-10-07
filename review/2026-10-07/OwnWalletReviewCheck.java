// Fixture helpers reused from review/2026-10-06/CoreReviewCheck.java.
// Isolated temporary wallet only; no user node, keys, network, or transactions.
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.minima.database.MinimaDB;
import org.minima.database.txpowtree.TxPoWTreeNode;
import org.minima.database.wallet.*;
import org.minima.objects.*;
import org.minima.objects.base.*;
import org.minima.system.params.GeneralParams;
import org.minima.utils.json.*;
public class OwnWalletReviewCheck {
 static int failures;
 static void check(String label,boolean pass){System.out.println((pass?"PASS ":"FAIL ")+label);if(!pass)failures++;}
 static void field(Object o,String name,Object val)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,val);}
 static TxPoWTreeNode node(ArrayList<Coin> coins)throws Exception{Constructor<TxPoWTreeNode> c=TxPoWTreeNode.class.getDeclaredConstructor();c.setAccessible(true);TxPoWTreeNode n=c.newInstance();field(n,"mCoins",coins);field(n,"mComputedRelevantCoins",coins);field(n,"mBlockNumber",new MiniNumber(100));return n;}
 static JSONArray coins(boolean own, boolean capped)throws Exception{
  org.minima.system.commands.search.coins cmd=new org.minima.system.commands.search.coins();cmd.getParams().put("relevant","true");
  if(own)cmd.getParams().put("own","true");if(capped)cmd.getParams().put("max","1");return (JSONArray)cmd.runCommand().get("response");
 }
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("own-wallet-review-").toFile();GeneralParams.DATA_FOLDER=dir.getPath();GeneralParams.USE_SQL_COINDB=false;GeneralParams.USE_BLOCK_AS_KEYUSES=false;
  MinimaDB.createDB();Wallet wallet=MinimaDB.getDB().getWallet();wallet.loadDB(new File(dir,"wallet"));
  ScriptRow own=wallet.addScript("RETURN TRUE",true,false,"0x01",true);
  ScriptRow watch=wallet.addScript("RETURN FALSE",false,false,"0x00",true);
  Coin tracked=new Coin(new MiniData("0x01"),new MiniData(watch.getAddress()),new MiniNumber(7),MiniData.ZERO_TXPOWID);
  Coin mine=new Coin(new MiniData("0x02"),new MiniData(own.getAddress()),new MiniNumber(3),MiniData.ZERO_TXPOWID);
  tracked.setBlockCreated(MiniNumber.ONE);mine.setBlockCreated(MiniNumber.ONE);
  field(MinimaDB.getDB().getTxPoWTree(),"mTip",node(new ArrayList<>(Arrays.asList(tracked,mine))));
  JSONArray capped=coins(true,true);
  check("own:true max:1 finds owned coin after tracked coin",capped.size()==1 && ((JSONObject)capped.get(0)).getString("coinid").equals("0x02"));
  check("plain relevant query preserves tracked and owned coins",coins(false,false).size()==2);
  check("own:true excludes watch coins",coins(true,false).size()==1);
  org.minima.system.commands.base.balance balance=new org.minima.system.commands.base.balance();
  JSONObject row=(JSONObject)((JSONArray)balance.runCommand().get("response")).get(0);
  check("default balance totals owned coins only",row.getString("confirmed").equals("3")&&row.getString("coins").equals("1"));
  balance.getParams().put("address",watch.getAddress());
  row=(JSONObject)((JSONArray)balance.runCommand().get("response")).get(0);
  check("explicit watched address balance remains available",row.getString("confirmed").equals("7"));
  wallet.saveDB(false);System.out.println("Failures: "+failures);System.exit(failures==0?0:1);
 }
}
