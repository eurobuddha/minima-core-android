import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.minima.database.MinimaDB;
import org.minima.database.txpowtree.TxPoWTreeNode;
import org.minima.database.wallet.*;
import org.minima.objects.*;
import org.minima.objects.base.*;
import org.minima.system.brains.TxPoWSearcher;
import org.minima.system.commands.txn.txnsign;
import org.minima.system.commands.backup.vault;
import org.minima.system.params.GeneralParams;

public class CoreReviewCheck {
 static int failures;
 static void check(String label,boolean pass){System.out.println((pass?"PASS ":"FAIL ")+label);if(!pass)failures++;}
 static void field(Object o,String name,Object val)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,val);}
 static TxPoWTreeNode node(ArrayList<Coin> coins)throws Exception{Constructor<TxPoWTreeNode> c=TxPoWTreeNode.class.getDeclaredConstructor();c.setAccessible(true);TxPoWTreeNode n=c.newInstance();field(n,"mCoins",coins);field(n,"mComputedRelevantCoins",coins);field(n,"mBlockNumber",new MiniNumber(100));return n;}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("core-review-").toFile();GeneralParams.DATA_FOLDER=dir.getPath();GeneralParams.USE_SQL_COINDB=false;GeneralParams.USE_BLOCK_AS_KEYUSES=false;
  MinimaDB.createDB();field(MinimaDB.getDB(),"mTxnDB",new org.minima.database.userprefs.txndb.TxnDB());Wallet wallet=MinimaDB.getDB().getWallet();wallet.loadDB(new File(dir,"wallet"));
  for(String invalid:new String[]{"4294967296","0.5","-4294967296"}){
   MinimaDB.getDB().getCustomTxnDB().createTransaction(invalid);
   txnsign cmd=new txnsign();cmd.getParams().put("id",invalid);cmd.getParams().put("publickey","custom");cmd.getParams().put("privatekey","0x01");cmd.getParams().put("keyuses",invalid);
   boolean refused=false;try{cmd.runCommand();}catch(Exception e){refused=true;}check("custom keyuses "+invalid+" refused",refused);
  }
  MinimaDB.getDB().getCustomTxnDB().createTransaction("last-leaf");
  txnsign lastLeaf=new txnsign();lastLeaf.getParams().put("id","last-leaf");lastLeaf.getParams().put("publickey","custom");lastLeaf.getParams().put("privatekey","0x02");lastLeaf.getParams().put("keyuses","262143");
  boolean signed=false;try{lastLeaf.runCommand();signed=true;}catch(Exception e){}
  check("last valid legacy leaf remains usable",signed);
  ScriptRow script=wallet.createNewSimpleAddress(false);String pk=script.getPublicKey();wallet.updateAllKeyUses(262144);
  MinimaDB.getDB().getCustomTxnDB().createTransaction("locked");vault.passwordLockDB("ReviewPassword123");
  txnsign locked=new txnsign();locked.getParams().put("id","locked");locked.getParams().put("publickey",pk);locked.getParams().put("password","ReviewPassword123");
  try{locked.runCommand();}catch(Exception expected){}check("capacity refusal relocks wallet",!wallet.isBaseSeedAvailable());
  Coin contract=new Coin(new MiniData("0x01"),new MiniData("0x99"),MiniNumber.ONE,MiniData.ZERO_TXPOWID);
  Coin simple=new Coin(new MiniData("0x02"),new MiniData(script.getAddress()),MiniNumber.ONE,MiniData.ZERO_TXPOWID);
  TxPoWTreeNode tip=node(new ArrayList<>(Arrays.asList(contract,simple)));
  ArrayList<Coin> result=TxPoWSearcher.searchCoins(tip,false,false,MiniData.ZERO_TXPOWID,false,MiniNumber.ZERO,false,MiniData.ZERO_TXPOWID,false,MiniData.ZERO_TXPOWID,false,"",false,true,Integer.MAX_VALUE,false,1);
  check("max:1 sendable:true finds later simple coin",result.size()==1&&result.get(0).getCoinID().isEqual(simple.getCoinID()));
  contract.setBlockCreated(new MiniNumber(99)); simple.setBlockCreated(new MiniNumber(10));
  field(MinimaDB.getDB().getTxPoWTree(),"mTip",tip);
  org.minima.system.commands.search.coins age=new org.minima.system.commands.search.coins();
  age.getParams().put("max","1"); age.getParams().put("coinage","50");
  org.minima.utils.json.JSONArray aged=(org.minima.utils.json.JSONArray)age.runCommand().get("response");
  check("max:1 applies after coinage",aged.size()==1 && ((org.minima.utils.json.JSONObject)aged.get(0)).getString("coinid").equals(simple.getCoinID().to0xString()));
  org.minima.database.txpowtree.CoinDB.createCoinDB(new File(dir,"coins"));
  org.minima.database.txpowtree.CoinDB cdb=org.minima.database.txpowtree.CoinDB.getTxPoWTreeCoinDB();
  cdb.insertCoin(MiniData.ZERO_TXPOWID,simple,new MiniNumber(100));
  Field connection=org.minima.utils.SqlDB.class.getDeclaredField("mSQLConnection");connection.setAccessible(true);
  try(java.sql.Statement sql=((java.sql.Connection)connection.get(cdb)).createStatement()) {
   for(String column:new String[]{"coinid","txpowtreeid","blockheight"}) {
    String value=column.equals("blockheight")?"100":"'0x02'";
    try(java.sql.ResultSet plan=sql.executeQuery("EXPLAIN SELECT * FROM coins WHERE "+column+"="+value)) {
     plan.next();check("CoinDB indexed "+column,!plan.getString(1).contains("tableScan"));
    }
   }
  }
  MinimaDB.getDB().getUserDB().setLastBlockAsKeyUses(new MiniNumber(268435455));
  boolean exhausted=false;try{org.minima.system.commands.base.block.getCurrentBlockAsKeyUses(0,268435456);}catch(IllegalArgumentException e){exhausted=true;}
  check("block capacity refusal preserves global counter",exhausted&&MinimaDB.getDB().getUserDB().getLastBlockAsKeyUses().isEqual(new MiniNumber(268435455)));
  for(String count:new String[]{"-1","0.5","4294967296","2147483647"}) {
   java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
   MiniNumber.WriteToStream(out,1);new org.minima.objects.mmr.MMR().writeDataStream(out);new MiniNumber(count).writeDataStream(out);out.close();
   boolean refused=false;try{new org.minima.objects.mmr.MegaMMR().readDataStream(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));}catch(java.io.IOException e){refused=true;}
   check("invalid/truncated MegaMMR coin count "+count+" rejected without allocation failure",refused);
  }
  GeneralParams.USE_SQL_COINDB=false;
  TxPoWTreeNode empty=node(new ArrayList<>());
  MinimaDB.getDB().getMegaMMR().getAllCoins().put(simple.getCoinID().to0xString(),simple);
  Field lockField=MinimaDB.class.getDeclaredField("mRWLock");lockField.setAccessible(true);
  java.util.concurrent.locks.ReentrantReadWriteLock lock=(java.util.concurrent.locks.ReentrantReadWriteLock)lockField.get(MinimaDB.getDB());
  try {TxPoWSearcher.searchCoins(empty,false,false,MiniData.ZERO_TXPOWID,false,MiniNumber.ZERO,false,MiniData.ZERO_TXPOWID,false,MiniData.ZERO_TXPOWID,false,"",false,false,Integer.MAX_VALUE,true,1,c -> {throw new IllegalStateException("injected");});}catch(IllegalStateException expected){}
  check("MegaMMR search exception releases read lock",lock.getReadHoldCount()==0);
  ArrayList<Coin> megaresult=TxPoWSearcher.searchCoins(empty,false,false,MiniData.ZERO_TXPOWID,false,MiniNumber.ZERO,false,MiniData.ZERO_TXPOWID,false,MiniData.ZERO_TXPOWID,false,"",false,false,Integer.MAX_VALUE,true,1);
  check("MegaMMR capped result releases read lock",megaresult.size()==1&&lock.getReadHoldCount()==0);
  GeneralParams.IS_MEGAMMR=true;field(MinimaDB.getDB().getTxPoWTree(),"mTip",empty);
  try{TxPoWSearcher.getToken(simple.getTokenID());}catch(Exception expected){}
  check("MegaMMR token lookup releases read lock",lock.getReadHoldCount()==0);
  cdb.saveDB(false);
  wallet.saveDB(false);System.out.println("Failures: "+failures);System.exit(failures==0?0:1);
 }
}
