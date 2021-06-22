import java.io.*;
import java.net.*;
import java.util.Date;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.Collections;

public class Router{
    private ArrayList<TableData> table;
    private String name;

    public Router(String name){
        table = new ArrayList<TableData>();
        this.name = name;

        Thread updateTable = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    updateTable();
                }catch(Exception e){
                    System.out.println("Erro:" + e.getMessage());
                }
            }
        });
        updateTable.start();
    }

    public boolean createPort(int dst_port, int exit_port){
        //TODO verify ports
        TableData myself = new TableData(dst_port, 0, 0);
        TableData port = new TableData(exit_port, 1, dst_port);

        table.add(myself);
        table.add(port);

        Collections.sort(table);

        Thread receiveTables = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    receivePort(dst_port);
                }catch(Exception e){
                    System.out.println("Erro:" + e.getMessage());
                }
            }
        });
        receiveTables.start();

        return true;
    }

    public void printTable(){
        System.out.println("Minhas portas:");
        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            System.out.println(aux.dst_port+" - "+ aux.metric +" - "+aux.exit_port);
        }
    }

    public void updateTable() throws Exception{
        while(true){
            ArrayList<TableData> newTable = new ArrayList<TableData>();

            int now = (int) (new Date().getTime()/1000);
            now -= 30; // remove 30 segundos

            for(int i = 0; i < table.size(); i++){
                TableData aux = table.get(i);
                if(aux.metric != 0){
                    if(aux.timestamp > now){
                        newTable.add(aux);
                    }else{
                        System.out.println("Excluindo a porta "+aux.dst_port+" da minha RIPtable");
                    }
                }else{
                    newTable.add(aux);
                }
            }

            Collections.sort(newTable);

            table = newTable;

            Thread.sleep(1000);
        }
    }

    private String encodeTable(){
        String response = "";
        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            response += aux.dst_port+"-"+aux.metric+"-"+aux.exit_port+"//";
        }

        if(response.length() > 2)
            return response.substring(0, response.length() - 2);
        
        return response;
    }

    private ArrayList<TableData> decodeTable(String data){
        ArrayList<TableData> auxTables = new ArrayList<TableData>();
        String[] aux = data.split("//");
        
        for(int i = 0; i < aux.length; i++){
            String[] rows = aux[i].split("-");
            TableData row = new TableData(Integer.parseInt(rows[0]), Integer.parseInt(rows[1]), Integer.parseInt(rows[2]));
            auxTables.add(row);
        }

        return auxTables;
    }

    public boolean sendTable() throws Exception{
        // System.out.println("Enviando tabelas");

        // A cada 10 segundos enviar a sua tabela para os vizinhos (metrica = 1)
        String message = "RIPtable//";
        message += encodeTable();

        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            
            if(aux.metric == 1){
                String messageByRouter = aux.exit_port+"//" + message;
                byte[] sendData = messageByRouter.getBytes();
                
                DatagramSocket tableSocket = new DatagramSocket();
                InetAddress IPAddress = InetAddress.getByName("localhost");
                    
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, aux.dst_port);
                // System.out.println("envianu: "+aux.dst_port);

                tableSocket.send(sendPacket);
                tableSocket.close();
            }
        }
        
        return true;
    }

    private TableData searchPort(int port){
        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            if(aux.dst_port == port)
                return aux;
        }

        return null;
    }

    public void receiveTable(int fromPort, String data){
        // System.out.println("chego em mim da porta "+fromPort+": "+data);
        ArrayList<TableData> auxTable = decodeTable(data);

        for(int i = 0; i < auxTable.size(); i++){
            TableData row = auxTable.get(i);
            
            TableData aux = searchPort(row.dst_port);
            if(aux == null){
                row.metric += 1;
                row.exit_port = fromPort;
                table.add(row);
            }else{
                aux.update();
            }
            
        }
    }

    public void receivePort(int port) throws Exception{
        // Ouvir as minhas portas
        while(true){
            DatagramSocket tableSocket = new DatagramSocket(port);
            byte[] receiveData = new byte[250];

            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            tableSocket.receive(receivePacket);
            String data = new String(receivePacket.getData(), "UTF-8").trim();

            // System.out.println("Recebi mensagem: "+data);
            try{
                String[] fields = data.split("//", 3);
                int headerPort = Integer.parseInt(fields[0]);
                String header = fields[1];
                String data_field = fields[2];

                switch(header){
                    case "RIPtable":
                            receiveTable(headerPort, data_field);
                        break;
                    
                    case "msg":
                            System.out.println("Recebi um bgl");
                            if(isMyPort(headerPort)){
                                System.out.println(data_field);
                            }else{
                                sendMessage(headerPort, data_field);
                            }
                        break;

                    case "arq":
                        break;

                    default:
                        //Numsei
                }
            }catch(Exception e){
                System.out.println("Erro inesperado: "+ e.getMessage());
            }
            //TODO Tratar UDP
            
            tableSocket.close();
        }
    }

    public boolean isMyPort(int port){
        for(int i = 0; i < table.size(); i++){
            TableData row = table.get(i);
            if(row.metric == 0 && row.dst_port == port){
                return true;
            }
        }

        return false;
    }

    public int searchPath(int dst){
        TableData aux = searchPort(dst);
        if(aux == null)
            return -1;
        
        if(isMyPort(aux.exit_port)){
            return aux.dst_port;
        }

        return searchPath(aux.exit_port);
    }

    public void sendMessage(int dst, String _message) throws Exception{
        String message = dst+"//msg//"+_message;
        int sendTo = searchPath(dst);

        if(sendTo == -1){
            return;
        }

        byte[] sendData = message.getBytes();
        
        DatagramSocket tableSocket = new DatagramSocket();
        InetAddress IPAddress = InetAddress.getByName("localhost");
            
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, sendTo);

        tableSocket.send(sendPacket);
        tableSocket.close();

    }

    public static void main(String[] args){
        Scanner sc = new Scanner(System.in);

        String myname = sc.next();
        Router self = new Router(myname);

        // Rotear
        Thread sendTable = new Thread(new Runnable(){
            @Override
            public void run() {
                try{
                    while(true){
                        // System.out.println("It");
                        self.sendTable();
                        Thread.sleep(10000);
                    }
                }catch(Exception e){
                    System.out.println("Erro:" + e.getMessage());
                }
            }
        });
        sendTable.start();


        while(sc.hasNext()){
            String line = sc.nextLine();
            String[] splitLine = line.split(" ", 2);

            String command = splitLine[0];
            String str = "";

            if(splitLine.length >= 2)
                str = splitLine[1];

            switch(command){
                case "/c":
                        // splitLine is [0] = dst_port, [1] = exit_port
                        System.out.println(str);
                        String[] strSplit = str.split(" ");
                        int dst_port = Integer.parseInt(strSplit[0]);
                        int exit_port = Integer.parseInt(strSplit[1]);

                        boolean response = self.createPort(dst_port, exit_port);

                        System.out.println("Criei a porta "+dst_port+" conectada com "+exit_port+" com: "+response);
                    break;
                case "/l":
                        self.printTable();
                    break;
                case "/msg":
                        String[] strSplit2 = str.split(" ", 2);
                        int dst_port2 = Integer.parseInt(strSplit2[0]);
                        String message = strSplit2[1];
                    
                        try{
                           self.sendMessage(dst_port2, message);
                        }catch(Exception e){
                            System.out.println("Erro:" + e.getMessage());
                        }
                    break;
                
                case "/arq":
                    break;
                
                //TODO
            }
        }
    }
}