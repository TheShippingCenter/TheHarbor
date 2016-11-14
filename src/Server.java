import java.net.*;
import java.io.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.xml.bind.DatatypeConverter;

import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.ResultSet;

class Server
{
    static Connection sql_connection;
    static OutputStream out;
    static InputStream in;

    public static void main(String[] args) throws IOException
    {
        ServerSocket serverSocket = null;

        String sql_url = "jdbc:mysql://localhost:3306/harbor?useSSL=false";
        String sql_user = "harborCaptain";
        String sql_passwd = "harborCaptain";


        // Initialize the SQL Server
        try
        {
            // Assumes the following MySQL Setup:
            // Database: harbor
            // Table: Matches
            // Col 1: Person1 VARCHAR(100)
            // Col 2: Person2 VARCHAR(100)
            // Col 3: Decision INT
            sql_connection = DriverManager.getConnection(sql_url, sql_user, sql_passwd);
        }
        catch(SQLException e)
        {
            System.err.println("Something's not right; SQL init failed");
        }

        // Initiate Server Socket
        try
        {
            serverSocket = new ServerSocket(1430);
        }
        catch(IOException e)
        {
			System.err.println("Could not listen on port: 1430");
            System.exit(1);
        }

        // Do Forever: Keep accepting client conections
        boolean running = true;
		Socket clientSocket = null;
        while(running)
        {
            try
            {
                clientSocket = serverSocket.accept();
            }
            catch (IOException e)
            {
                System.err.println("Accept failed");
                System.exit(1);
            }

            in = clientSocket.getInputStream();
            out = clientSocket.getOutputStream();


            // Parse the HTTP Request Header and send a response back to initiate a handshake
            // Lots of code borrowed from below:
            // https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_a_WebSocket_server_in_Java
            String headerData = new Scanner(in, "UTF-8").useDelimiter("\\r\\n\\r\\n").next();
            Matcher get = Pattern.compile("^GET").matcher(headerData);

            if(get.find())
            {
                Matcher match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(headerData);
                match.find();

                try
                {
                    byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Sec-WebSocket-Accept: "
                            + DatatypeConverter
                            .printBase64Binary(
                                MessageDigest
                                    .getInstance("SHA-1")
                                    .digest((match.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                            .getBytes("UTF-8")))
                            + "\r\n\r\n")
                            .getBytes("UTF-8");

                    out.write(response, 0, response.length);
                    System.out.println("Sent HTTP Response Header");
                }
                catch (NoSuchAlgorithmException e)
                {
                    e.printStackTrace();
                }

                // Read the message and decode it
                String data = receiveDecodedMessage();
                String[] shipped = data.split("--");

                //for(String s : shipped)
                //    System.out.println(s);

                String p1 = null;
                String p2 = null;
                if(shipped.length == 3) // If 2 people are received, alphabetize them
                {
                    if(shipped[1].compareTo(shipped[2]) < 0)
                    {
                        p1 = shipped[1];
                        p2 = shipped[2];
                    }
                    else
                    {
                        p1 = shipped[2];
                        p2 = shipped[1];
                    }
                }
                else
                {
                    // Else, probably 1 person was sent
                    p1 = shipped[1];
                }

                switch(data.charAt(0))
                {
                    case 'r':
                        break;
                    case 'c':
                        createMatch(p1, p2);
                        break;
                    case 'a':
                        acceptMatch(p1, p2);
                        break;
                    case 'd':
                        rejectMatch(p1, p2);
                        break;
                    default:
                }
            }
        }
        serverSocket.close();
    }

    private static void createMatch(String p1, String p2)
    {
        System.out.println("Creating Match");
        System.out.println("Person 1: " + p1 + "; Person 2: " + p2);

        try
        {
            PreparedStatement insMatchPst;
            insMatchPst = sql_connection.prepareStatement("INSERT INTO Matches(person1, person2, decision) VALUES(?, ?, ?)");
            insMatchPst.setString(1, p1);
            insMatchPst.setString(2, p2);
            insMatchPst.setInt(3, 0);
            insMatchPst.executeUpdate();
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            System.err.println("SQL wtf");
        }
    }

    private static void acceptMatch(String p1, String p2)
    {
        System.out.println("Accepting Match");
        System.out.println("Person 1: " + p1 + "; Person 2: " + p2);

        try
        {
            PreparedStatement acceptMatchPst;
            acceptMatchPst = sql_connection.prepareStatement("SELECT Decision FROM Matches WHERE person1=? AND person2=?");
            acceptMatchPst.setString(1, p1);
            acceptMatchPst.setString(2, p2);

            ResultSet rs = acceptMatchPst.executeQuery();
            if(rs.next())
            {
                int decision = rs.getInt("Decision");
                
				PreparedStatement updateMatchPst;
                switch(decision)
                {
                    case 0:
                        updateMatchPst = sql_connection.prepareStatement("UPDATE Matches SET decision=1 where person1=? and person2=?");
                        updateMatchPst.setString(1, p1);
                        updateMatchPst.setString(2, p2);
                        updateMatchPst.executeUpdate();
                        break;
                    case 1:
                        updateMatchPst = sql_connection.prepareStatement("UPDATE Matches SET decision=2 where person1=? and person2=?");
                        updateMatchPst.setString(1, p1);
                        updateMatchPst.setString(2, p2);
                        updateMatchPst.executeUpdate();

                        // Also notify people about updates
                        notifyPeople(p1, p2);
                        break;
                    case 2:
                        notifyPeople(p1, p2);
                        break;
                    default:
                        System.err.println("SQL Error: Bad Decision in Table");
                }
            }
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            System.err.println("SQL wtf");
        }
    }

    private static void rejectMatch(String p1, String p2)
    {
        System.out.println("Rejecting Match");
        System.out.println("Person 1: " + p1 + "; Person 2: " + p2);

        try
        {
            PreparedStatement rmMatchPst;
            rmMatchPst = sql_connection.prepareStatement("DELETE from Matches WHERE person1=? AND person2=?");
            rmMatchPst.setString(1, p1);
            rmMatchPst.setString(2, p2);
            rmMatchPst.executeUpdate();
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            System.err.println("SQL wtf");
        }
    }

    private static void notifyPeople(String p1, String p2)
    {
        System.out.println("TODO: NOTIFY PEOPLE");
        // Send a message back
        int payloadSize = p1.length() + p2.length();
        int messageSize = 2 + payloadSize;

        System.out.println("Sending message: ");
        byte[] message = new byte[256];
        message[0] = (byte) 0x81;
        message[1] = (byte) messageSize;

        for(int i = 0; i < p1.length(); i++)
            message[i + 2] = (byte) p1.charAt(i);

        message[2 + p1.length()] = '-';
        message[3 + p1.length()] = '-';

        for(int i = 0; i < p2.length(); i++)
            message[i + 4 + p1.length()] = (byte) p2.charAt(i);

        //String toSend = "";
        //for(int i = 0; i < messageSize; i++)
        //    toSend += (char) message[i];
        //System.out.println(toSend);

        try
        { 
            out.write(message, 0, messageSize + 2);
        }
        catch (IOException e)
        {
            System.err.println("Sending: Something went wrong");
            e.printStackTrace();
        }
    }

    private static String receiveDecodedMessage()
    {
        byte[] buffer = new byte[256];

        try
        {
            in.read(buffer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        int size = removeSign(buffer[1]) - 128;

        byte[] decoded = new byte[size];
        byte[] key = new byte[4];

        for(int i = 0; i < 4; i++)
            key[i] = buffer[i + 2];

        for(int i = 0; i < size; i++)
            decoded[i] = (byte) (buffer[i + 6] ^ key[i & 0x3]);

        String data = "";
        for(byte c : decoded)
            data += (char) c;

        return data;
    }

    private static int removeSign(byte b)
    {
        return (int) (((char) b) & 0xFF);
    }
}
