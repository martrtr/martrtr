package io.openai.footdraw;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class Store extends SQLiteOpenHelper {
    static final class Event { long seq, epoch, ts, id; String stroke, kind; double x, y, p; }
    private final SharedPreferences prefs;
    Store(Context c) { super(c, "footdraw.db", null, 1); prefs = c.getSharedPreferences("footdraw", Context.MODE_PRIVATE); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE pending(seq INTEGER PRIMARY KEY, epoch INTEGER, stroke TEXT, kind TEXT, x REAL, y REAL, p REAL, ts INTEGER)"); db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, epoch INTEGER, stroke TEXT, kind TEXT, x REAL, y REAL, p REAL, ts INTEGER)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}
    synchronized long nextSeq() { long s=prefs.getLong("seq",0)+1; prefs.edit().putLong("seq",s).apply(); return s; }
    synchronized long epoch() { return prefs.getLong("epoch",1); }
    synchronized double vx() { return Double.longBitsToDouble(prefs.getLong("vx",Double.doubleToLongBits(0))); }
    synchronized double vy() { return Double.longBitsToDouble(prefs.getLong("vy",Double.doubleToLongBits(0))); }
    synchronized void setView(double x,double y){prefs.edit().putLong("vx",Double.doubleToLongBits(x)).putLong("vy",Double.doubleToLongBits(y)).apply();}
    synchronized boolean syncEpoch(long e){if(epoch()==e)return false;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("pending",null,null);db.delete("points",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}prefs.edit().putLong("epoch",e).apply();return true;}
    synchronized void clearTo(long e){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("pending",null,null);db.delete("points",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}prefs.edit().putLong("epoch",e).apply();}
    synchronized Event add(String stroke,String kind,double x,double y,double p){Event e=new Event();e.seq=nextSeq();e.epoch=epoch();e.stroke=stroke;e.kind=kind;e.x=x;e.y=y;e.p=p;e.ts=System.currentTimeMillis();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("seq",e.seq);v.put("epoch",e.epoch);v.put("stroke",stroke);v.put("kind",kind);v.put("x",x);v.put("y",y);v.put("p",p);v.put("ts",e.ts);db.insertOrThrow("pending",null,v);ContentValues q=new ContentValues(v);q.remove("seq");e.id=db.insertOrThrow("points",null,q);db.setTransactionSuccessful();}finally{db.endTransaction();}return e;}
    synchronized void ack(long seq){getWritableDatabase().delete("pending","seq<=?",new String[]{Long.toString(seq)});}
    synchronized List<Event> pendingAfter(long after,int limit){ArrayList<Event> o=new ArrayList<>();try(Cursor c=getReadableDatabase().query("pending",null,"seq>?",new String[]{Long.toString(after)},null,null,"seq ASC",Integer.toString(limit))){while(c.moveToNext())o.add(from(c,false));}return o;}
    synchronized List<Event> points(){ArrayList<Event> o=new ArrayList<>();try(Cursor c=getReadableDatabase().query("points",null,null,null,null,null,"id ASC")){while(c.moveToNext())o.add(from(c,true));}return o;}
    private Event from(Cursor c,boolean point){Event e=new Event();if(point)e.id=c.getLong(c.getColumnIndexOrThrow("id"));else e.seq=c.getLong(c.getColumnIndexOrThrow("seq"));e.epoch=c.getLong(c.getColumnIndexOrThrow("epoch"));e.stroke=c.getString(c.getColumnIndexOrThrow("stroke"));e.kind=c.getString(c.getColumnIndexOrThrow("kind"));e.x=c.getDouble(c.getColumnIndexOrThrow("x"));e.y=c.getDouble(c.getColumnIndexOrThrow("y"));e.p=c.getDouble(c.getColumnIndexOrThrow("p"));e.ts=c.getLong(c.getColumnIndexOrThrow("ts"));return e;}
}
