import java.util.Date;
import java.lang.Comparable;

public class TableData implements Comparable<TableData> {
    public int dst_port, exit_port;
    public int metric;
    public int timestamp = -1;

    public TableData(){
        // None
    }

    public TableData(int dst_port, int metric, int exit_port){
        this.dst_port = dst_port;
        this.metric = metric;
        this.exit_port = exit_port;
        this.timestamp = (int) (new Date().getTime()/1000);
    }

    @Override
    public int compareTo(TableData data2){
        if(this.metric > data2.metric)
            return 1;
        return -1;
    }

    public String toString(){
        return "Sou: "+dst_port+"-"+metric+"-"+exit_port;
    }

    public void update(){
        this.timestamp = (int) (new Date().getTime()/1000);
    }
}