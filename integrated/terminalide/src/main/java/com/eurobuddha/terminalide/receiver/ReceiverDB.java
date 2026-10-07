package com.eurobuddha.terminalide.receiver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

public class ReceiverDB extends SQLiteOpenHelper {

    // If you change the database schema, you must increment the database version.
    public static final int DATABASE_VERSION            = 1;
    public static final String DATABASE_NAME            = "minimaevents.db";
    public static final String DATABASE_EVENT_TABLE     = "events";

    //The Handle to the DB
    SQLiteDatabase mDB;

    //All the Columns
    String[] ALL_COLUMNS = new String[] {"_id","event","data","date"};

    // Database creation sql statement
    private static final String DATABASE_CREATE_EVENTS = "create table if not exists "+DATABASE_EVENT_TABLE+" ( " +
            "_id integer primary key," +
            "event text not null," +
            "data text not null," +
            "date integer not null" +
            ");";

    public ReceiverDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);

        //Get handle..
        mDB = getWritableDatabase();
    }

    public boolean isOpen(){
        return mDB.isOpen();
    }

    public void reOpen(){
        if(mDB.isOpen()){
            mDB.close();
        }

        mDB = getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(DATABASE_CREATE_EVENTS);
    }

    public void wipeDB(){
        mDB.execSQL("DROP TABLE IF EXISTS "+DATABASE_EVENT_TABLE);
        onCreate(mDB);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        wipeDB();
    }
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        wipeDB();
    }

    public void insertEvent(String zEvent, String zData){

        ContentValues values = new ContentValues();
        values.put("event", zEvent);
        values.put("data", zData);
        values.put("date", System.currentTimeMillis());

        mDB.insert(DATABASE_EVENT_TABLE, null, values);
    }

    private JSONObject convertCursor(Cursor zCursor){
        JSONObject app = new JSONObject();

        try{
            app.put("_id", zCursor.getInt(0));
            app.put("event", zCursor.getString(1));
            app.put("data", zCursor.getString(2));
            app.put("date", zCursor.getLong(3));
        } catch (Exception e) {

        }

        return app;
    }

    public JSONArray getAllMessages() {

        JSONArray results = new JSONArray();

        //String[] args = new String[] {zPackageName, zpackageID, zMinimaID};
        Cursor cursor = mDB.query(false, DATABASE_EVENT_TABLE, ALL_COLUMNS,null, null, null, null, null, null);
        if (cursor != null) {

            try {
                while (cursor.moveToNext()) {
                    results.put(convertCursor(cursor));
                }
            } finally {
                cursor.close();
            }
        }

        return results;
    }

    public void deleteOldMessages(){

        //Get the data 1 week ago..
        long weekmilli = 1000 * 60 * 60 * 24 * 7;
        //long weekmilli = 1000 * 60 * 5;
        long ctime = System.currentTimeMillis() - weekmilli;

        mDB.delete(DATABASE_EVENT_TABLE, "date<?", new String[]{""+ctime});
    }
}
