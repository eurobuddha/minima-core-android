package org.minimarex.minimacore.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.minima.Minima;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.utils.logger;

import java.util.Objects;

public class MinimaReceiver extends BroadcastReceiver {

    private Minima mMinima;

    ReceiverDB mDatabase;

    public MinimaReceiver(Minima zMinima, Context zContext){
        super();

        //Store for later
        mMinima = zMinima;

        mDatabase = new ReceiverDB(zContext);
        //mDatabase.wipeDB();
    }

    public ReceiverDB getDatabase(){
        return mDatabase;
    }

    public void onDestroy(){
        mDatabase.close();
    }

    @Override
    public void onReceive(Context zContext, Intent zIntent) {

        String action = zIntent.getAction();
        logger.log("RECEIVED action : " + action);

        //Get the Extra data - that is ALWAYS Sent
        String frompackage      = zIntent.getStringExtra(MinimaMessages.MINIMA_API_PACKAGE_CLASS);
        String frompackageuid   = zIntent.getStringExtra(MinimaMessages.MINIMA_API_APP_UID);
        String minimauid        = zIntent.getStringExtra(MinimaMessages.MINIMA_API_REGISTER_MINIMAID);
        String responseid       = zIntent.getStringExtra(MinimaMessages.MINIMA_API_RESPONSE_ID);

        if (Objects.equals(zIntent.getAction(), MinimaMessages.MINIMA_API_REGISTER)) {

            //CHECK AND ADD to DB
            if(!mDatabase.exists(frompackage, frompackageuid, minimauid)){

                //Add to the database..
                mDatabase.insertApp(frompackage, frompackageuid, minimauid);

                //Send response..
                sendResponse(zContext, frompackage, responseid, minimauid, getRegisterMessage("Registered OK!"));

            }else{
                //Send response..
                sendResponse(zContext, frompackage, responseid, minimauid, getRegisterMessage("Already registered.."));
            }

        } else if (Objects.equals(zIntent.getAction(), MinimaMessages.MINIMA_API_CMD)) {

            JSONObject app = mDatabase.selectApp(frompackage, frompackageuid, minimauid);
            if(app == null){
                logger.log("UNKNOWN Package for request : " + frompackage);
                return;
            }

            //Is it enabled..!
            if((int)app.get("penabled")!=1){
                logger.log("Package NOT enabled : " + frompackage);
                sendResponse(zContext, frompackage, responseid, minimauid, getErrorMessage("Package NOT enabled in Minima-Core!"));
                return;
            }

            //Is it an ADMIN user
            String Userid = "0xFF";
            if((int)app.get("admin")==1){
                Userid = "0x00";
            }

            String cmd      = zIntent.getStringExtra(MinimaMessages.MINIMA_API_CMD_ACTION);
            String result   = mMinima.runMinimaCMD(cmd, false,Userid);

            //Send it back..
            sendResponse(zContext, frompackage, responseid, minimauid, result);
        }
    }

    public void sendResponse(Context zContext, String zPackage, String zResponseID, String zMinimaID, String zResponse){

        //Create the Response intent
        Intent intent = new Intent(MinimaMessages.MINIMA_API_RESPONSE);

        //The MinimaID they expect
        intent.putExtra(MinimaMessages.MINIMA_API_REGISTER_MINIMAID, zMinimaID);

        //The ResponseID they expect
        intent.putExtra(MinimaMessages.MINIMA_API_RESPONSE_ID, zResponseID);

        //The REsponse Data
        intent.putExtra(MinimaMessages.MINIMA_API_RESPONSE_RESULT, zResponse);

        //Set to send ONLY back to original sender
        intent.setPackage(zPackage);

        //And broadcast
        zContext.sendBroadcast(intent);
    }

    public void sendNotify(Context zContext, String zPackage, String zMinimaID, String zNotifyMessage){

        //Create the Response intent
        Intent intent = new Intent(MinimaMessages.MINIMA_API_NOTIFY);

        //The MinimaID they expect
        intent.putExtra(MinimaMessages.MINIMA_API_REGISTER_MINIMAID, zMinimaID);

        //The Notify Data
        intent.putExtra(MinimaMessages.MINIMA_API_NOTIFY_DATA, zNotifyMessage);

        //Set to send ONLY back to original sender
        intent.setPackage(zPackage);

        //And broadcast
        zContext.sendBroadcast(intent);
    }

    private String getRegisterMessage(String zMessage){
        JSONObject ret = new JSONObject();
        ret.put("status",true);
        ret.put("response",zMessage);
        return ret.toString();
    }

    private String getErrorMessage(String zMessage){
        JSONObject ret = new JSONObject();
        ret.put("status",false);
        ret.put("response",zMessage);
        return ret.toString();
    }
}
