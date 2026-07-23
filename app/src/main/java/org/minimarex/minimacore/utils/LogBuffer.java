package org.minimarex.minimacore.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Static ring buffer for the node's log lines (MINIMALOG notify events).
 *
 * MinimaService appends every line as it arrives (NotifyManager thread) so logs
 * accumulate from service start whether or not the Logs tab is ever opened. The
 * LogsView attaches a sink to live-tail; the sink is invoked on the appender's
 * thread and must marshal to the UI itself.
 */
public final class LogBuffer {

    public interface Sink {
        void onLine(String zLine);
    }

    private static final int MAX_LINES = 600;

    private static final ArrayDeque<String> mLines = new ArrayDeque<>();

    private static Sink mSink = null;

    private LogBuffer() {}

    public static void append(String zLine){
        if(zLine == null){
            return;
        }

        Sink sink;
        synchronized (mLines){
            mLines.addLast(zLine);
            while(mLines.size() > MAX_LINES){
                mLines.removeFirst();
            }
            sink = mSink;
        }

        //Deliver OUTSIDE the lock
        if(sink != null){
            try{
                sink.onLine(zLine);
            }catch(Exception ignore){}
        }
    }

    /** All buffered lines, oldest first. */
    public static ArrayList<String> snapshot(){
        synchronized (mLines){
            return new ArrayList<>(mLines);
        }
    }

    public static void clear(){
        synchronized (mLines){
            mLines.clear();
        }
    }

    public static void setSink(Sink zSink){
        synchronized (mLines){
            mSink = zSink;
        }
    }

    public static void clearSink(Sink zSink){
        synchronized (mLines){
            if(mSink == zSink){
                mSink = null;
            }
        }
    }
}
