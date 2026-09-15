package io.openai.footdraw;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class Store extends SQLiteOpenHelper {
    static final int DEFAULT_BRUSH = 0xEBEEF4;
    static final class Event {
        long seq, epoch, ts, id;
        String stroke, kind;
        double x, y, p;
        int color;
    }

    private final SharedPreferences prefs;
    private volatile Runnable wake;

    Store(Context c) {
        super(c, "footdraw.db", null, 2);
        prefs = c.getSharedPreferences("footdraw", Context.MODE_PRIVATE);
        setWriteAheadLoggingEnabled(true);
    }
    void setWake(Runnable r){ wake=r; }
    private void signal(){ Runnable r=wake; if(r!=null)r.run(); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try { db.execSQL("PRAGMA synchronous=NORMAL"); } catch(Exception ignored) {}
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending(seq INTEGER PRIMARY KEY, epoch INTEGER, stroke TEXT, kind TEXT, x REAL, y REAL, p REAL, ts INTEGER, color INTEGER DEFAULT 15462132)");
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, epoch INTEGER, stroke TEXT, kind TEXT, x REAL, y REAL, p REAL, ts INTEGER, color INTEGER DEFAULT 15462132)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if(oldV<2){
            try{db.execSQL("ALTER TABLE pending ADD COLUMN color INTEGER DEFAULT 15462132");}catch(Exception ignored){}
            try{db.execSQL("ALTER TABLE points ADD COLUMN color INTEGER DEFAULT 15462132");}catch(Exception ignored){}
        }
    }

    synchronized long nextSeq() {
        long s = prefs.getLong("seq", 0) + 1;
        prefs.edit().putLong("seq", s).apply(); return s;
    }
    synchronized long epoch() { return prefs.getLong("epoch", 1); }
    synchronized double vx() { return Double.longBitsToDouble(prefs.getLong("vx", Double.doubleToLongBits(0))); }
    synchronized double vy() { return Double.longBitsToDouble(prefs.getLong("vy", Double.doubleToLongBits(0))); }
    synchronized void setView(double x, double y) { prefs.edit().putLong("vx",Double.doubleToLongBits(x)).putLong("vy",Double.doubleToLongBits(y)).apply(); }

    synchronized boolean syncEpoch(long e) {
        if (epoch() == e) return false;
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.delete("pending",null,null); db.delete("points",null,null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        prefs.edit().putLong("epoch",e).apply(); signal(); return true;
    }
    synchronized void clearTo(long e) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.delete("pending",null,null); db.delete("points",null,null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        prefs.edit().putLong("epoch",e).apply(); signal();
    }
    synchronized Event add(String stroke, String kind, double x, double y, double p, int color) {
        Event e=new Event(); e.seq=nextSeq(); e.epoch=epoch(); e.stroke=stroke; e.kind=kind; e.x=x; e.y=y; e.p=p; e.color=color&0xFFFFFF; e.ts=System.currentTimeMillis();
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v=new ContentValues(); v.put("seq",e.seq); v.put("epoch",e.epoch); v.put("stroke",stroke); v.put("kind",kind); v.put("x",x); v.put("y",y); v.put("p",p); v.put("ts",e.ts); v.put("color",e.color); db.insertOrThrow("pending",null,v);
            ContentValues q=new ContentValues(v); q.remove("seq"); q.put("epoch",e.epoch); e.id=db.insertOrThrow("points",null,q);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        signal(); return e;
    }
    synchronized void ack(long seq) { getWritableDatabase().delete("pending","seq<=?",new String[]{Long.toString(seq)}); }

    synchronized List<Event> pendingAfter(long after, int limit) {
        ArrayList<Event> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("pending",null,"seq>?",new String[]{Long.toString(after)},null,null,"seq ASC",Integer.toString(limit))) {
            while(c.moveToNext()) out.add(from(c,false));
        } return out;
    }
    synchronized List<Event> points() {
        ArrayList<Event> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("points",null,null,null,null,null,"id ASC")) { while(c.moveToNext()) out.add(from(c,true)); }
        return out;
    }
    private Event from(Cursor c, boolean point) {
        Event e=new Event();
        if(point)e.id=c.getLong(c.getColumnIndexOrThrow("id")); else e.seq=c.getLong(c.getColumnIndexOrThrow("seq"));
        e.epoch=c.getLong(c.getColumnIndexOrThrow("epoch")); e.stroke=c.getString(c.getColumnIndexOrThrow("stroke")); e.kind=c.getString(c.getColumnIndexOrThrow("kind"));
        e.x=c.getDouble(c.getColumnIndexOrThrow("x")); e.y=c.getDouble(c.getColumnIndexOrThrow("y")); e.p=c.getDouble(c.getColumnIndexOrThrow("p")); e.ts=c.getLong(c.getColumnIndexOrThrow("ts"));
        int ci=c.getColumnIndex("color"); e.color=ci>=0?c.getInt(ci):DEFAULT_BRUSH; return e;
    }
}
