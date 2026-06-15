package org.minimarex.minimacore.receiver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.utils.logger;

public class ReceiverDB extends SQLiteOpenHelper {
    // If you change the database schema, you must increment the database version.
    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "receive.db";

    // Database creation sql statement
    private static final String DATABASE_CREATE = "create table if not exists applications ( " +
            "_id integer primary key," +
            "package text not null," +
            "packageid text not null," +
            "minimaid text not null," +
            "penabled integer not null" +
            ");";

    public ReceiverDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(DATABASE_CREATE);
    }

    public void wipeDB(){
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DROP TABLE IF EXISTS applications");
        onCreate(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        wipeDB();
    }
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        wipeDB();
    }

    public void insertApp(String zPackageName, String zPackageID, String zMinimaID){

        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("package", zPackageName);
        values.put("packageid", zPackageID);
        values.put("minimaid", zMinimaID);
        values.put("penabled", 0);

        db.insert("applications", null, values);
    }

    public JSONArray selectAllApps() {
        SQLiteDatabase db = getWritableDatabase();

        JSONArray results = new JSONArray();

        String[] cols = new String[] {"package", "packageid", "minimaid", "penabled"};
        Cursor mCursor = db.query(false, "applications", cols,null, null, null, null, null, null);
        if (mCursor != null) {

            try {
                while (mCursor.moveToNext()) {
                    JSONObject app = new JSONObject();
                    app.put("package", mCursor.getString(0));
                    app.put("packageid", mCursor.getString(1));
                    app.put("minimaid", mCursor.getString(2));
                    app.put("penabled", mCursor.getInt(3));

                    results.add(app);
                }
            } finally {
                mCursor.close();
            }
        }

        return results;
    }

    public JSONObject selectApp(String zPackageName, String zpackageID, String zMinimaID){
        SQLiteDatabase db = getWritableDatabase();

        String[] cols = new String[] {"package", "packageid", "minimaid", "penabled"};
        String[] args = new String[] {zPackageName, zpackageID, zMinimaID};
        Cursor mCursor = db.query(true, "applications", cols,"package=? AND packageid=? AND minimaid=?",
                args, null, null, null, null);
        if (mCursor != null) {
            try {
                while (mCursor.moveToNext()) {
                    JSONObject app = new JSONObject();
                    app.put("package", mCursor.getString(0));
                    app.put("packageid", mCursor.getString(1));
                    app.put("minimaid", mCursor.getString(2));
                    app.put("penabled", mCursor.getInt(3));

                    return app;
                }
            } finally {
                mCursor.close();
            }
        }

        return null;
    }

    public boolean exists(String zPackageName, String zPackageID, String zMinimaID){
        return selectApp(zPackageName,zPackageID,zMinimaID) != null;
    }

    public void setEnabled(String zPackageName, boolean zEnabled){
        ContentValues cv = new ContentValues();
        if(zEnabled){
            cv.put("penabled",1);
        }else{
            cv.put("penabled",0);
        }

        SQLiteDatabase db = getWritableDatabase();
        db.update("applications", cv, "package=?", new String[]{zPackageName});
    }
}
