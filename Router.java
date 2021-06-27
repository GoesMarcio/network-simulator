import java.io.*;
import java.net.*;
import java.util.Date;
import java.util.Arrays;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.Collections;

public class Router{
    private ArrayList<TableData> table;
    private String name;
    private boolean log;

    public Router(String name){
        table = new ArrayList<TableData>();
        this.name = name;
        this.log = false;

        createFolder();

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

    private void createFolder(){
        File dir = new File("archives_routers/"+this.name);
        if (!dir.exists()) 
            dir.mkdirs();
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

        if(this.log)
            System.out.println("Criei a porta "+dst_port+" conectada com "+exit_port);

        return true;
    }

    public void printTable(){
        System.out.println("Minha RIPtable:");
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
                        if(this.log)
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

    private String encodeTable(int port){
        String response = "";
        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            if(aux.exit_port != port)
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

        for(int i = 0; i < table.size(); i++){
            TableData aux = table.get(i);
            
            if(aux.metric == 0){
                for(int j = 0; j < table.size(); j++){
                    TableData aux2 = table.get(j);
                    if(aux2.metric == 1 && aux2.exit_port == aux.dst_port){
                        String message = "RIPtable//";
                        message += encodeTable(aux2.dst_port);
                        String messageByRouter = aux2.exit_port+"//" + message;
                        byte[] sendData = messageByRouter.getBytes();
                        
                        DatagramSocket tableSocket = new DatagramSocket();
                        InetAddress IPAddress = InetAddress.getByName("localhost");
                            
                        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, aux2.dst_port);
                        
                        if(this.log) 
                            System.out.println("Enviando tabela RIP: p/ "+aux2.dst_port);

                        tableSocket.send(sendPacket);
                        tableSocket.close();
                    }
                }
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
        if(this.log)
            System.out.println("Recebi RIPtable de "+fromPort);

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
            byte[] receiveData = new byte[4000];

            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            tableSocket.receive(receivePacket);
            String data = new String(receivePacket.getData(), "UTF-8").trim();

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
                            if(isMyPort(headerPort)){
                                System.out.println(data_field);
                            }else{
                                sendMessage(headerPort, data_field);
                            }
                        break;

                    case "arq":
                            String[] archive_data = data_field.split("//");
                            if(isMyPort(headerPort)){
                                receiveArchive(archive_data[0], archive_data[1]);
                            }else{
                                resendArchive(headerPort, archive_data[0], archive_data[1]);
                            }
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

        if(this.log){
            if(dst == sendTo)
                System.out.println("Enviando mensagem para "+sendTo);
            else
                System.out.println("Reenviando mensagem para "+sendTo);
        }
    }

    public String convertByteToString(byte[] data){
        String ret = "";

        for(int i = 0; i < data.length; i++){
            ret += ((byte) data[i]) + ";";
        }

        return ret;
    }

    public byte[] convertStringToByte(String str){
        String[] aux = str.split(";");
        byte[] ret = new byte[aux.length];

        for(int i = 0; i < aux.length; i++){
            // System.out.println(aux[i]);
            ret[i] = (byte) Integer.parseInt(aux[i]);
        }

        return ret;
    }

    public void receiveArchive(String archive, String strByte) throws Exception{
        byte[] data = convertStringToByte(strByte);

        OutputStream outputStream = new FileOutputStream(new File("archives_routers/"+this.name+"/"+archive));
        outputStream.write(data);
        outputStream.close();
    }

    public void resendArchive(int dst, String archive, String archiveByte) throws Exception{

        String message = dst+"//arq//"+archive+"//"+archiveByte;
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

        if(this.log){
            System.out.println("Reenviando arquivo "+sendTo);
        }
        
    }

    public void sendArchive(int dst, String archive) throws Exception{
        File file = new File("archives_routers/"+this.name+"/"+archive);
        if(!file.exists()){
            System.out.println("O arquivo nao existe!");
            return;
        }

        InputStream inputStream = new FileInputStream(file);
        int byteRead;
        byte[] data = new byte[(int) file.length()];
        int j = 0;
        while ((byteRead = inputStream.read()) != -1) {
            data[j] = (byte) byteRead;
            j++;
        }

        String archiveByte = convertByteToString(data);

        String message = dst+"//arq//"+archive+"//"+archiveByte;
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

        if(this.log){
            if(dst == sendTo)
                System.out.println("Enviando mensagem para "+sendTo);
            else
                System.out.println("Reenviando mensagem para "+sendTo);
        }
    }

    public void log(){
        this.log = !this.log;
    }

    public void printHelp(){
        
        String message = "\u001B[43m\u001B[30mComandos\u001B[0m:\n";

        message += "\u001B[43m\u001B[30m/lst\u001B[0m - Exibe a RIPtable do roteador.\n";
        message += "\u001B[43m\u001B[30m/c port port\u001B[0m - Cria conexao de uma porta do roteador com outra porta.\n";
        message += "\u001B[43m\u001B[30m/msg port text\u001B[0m - Envia uma mensagem para um roteador atraves de uma porta.\n";
        message += "\u001B[43m\u001B[30m/arc port file\u001B[0m - Envia um arquivo para um roteador atraves de uma porta.\n";
        message += "\u001B[43m\u001B[30m/help\u001B[0m - Exibe os comandos do roteador.\n";
        message += "\u001B[43m\u001B[30m/log\u001B[0m - Ativa/desativa mensagens de log.\n";
        message += "\u001B[43m\u001B[30m/shutdown\u001B[0m - Desliga o roteador.\n";
            
        System.out.println(message);
    }

    public static void main(String[] args) throws Exception{
        Scanner sc = new Scanner(System.in);

        String myname = args[0];
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
            try{
                String line = sc.nextLine();
                String[] splitLine = line.split(" ", 2);

                String command = splitLine[0];
                String str = "";

                if(splitLine.length >= 2)
                    str = splitLine[1];

                switch(command){
                    case "/c":
                            // splitLine is [0] = dst_port, [1] = exit_port
                            String[] strSplit = str.split(" ");
                            int dst_port = Integer.parseInt(strSplit[0]);
                            int exit_port = Integer.parseInt(strSplit[1]);

                            boolean response = self.createPort(dst_port, exit_port);
                        break;
                    case "/lst":
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
                    
                    case "/arc":
                            String[] strSplit3 = str.split(" ", 2);
                            int dst_port3 = Integer.parseInt(strSplit3[0]);
                            String archive = strSplit3[1];
                            
                            try{
                                self.sendArchive(dst_port3, archive);
                            }catch(Exception e){
                                System.out.println("Erro:" + e.getMessage());
                            }
                        break;
                    
                    case "/help":
                            self.printHelp();
                        break;
                    case "/log":
                            self.log();
                        break;
                    case "/shutdown":
                            System.exit(0);
                        break;
                    
                    //TODO
                }
            }catch(Exception e){
                System.out.println("Erro:" + e.getMessage());
            }
        }
    }
}