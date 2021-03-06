package com.mobilegenomics.f5n.support;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;

public class FileServer {
    public static ServerSocket serverSocket = null;
    private static String fileServerDir;

    public static void startFileServer(int port, String pathToDir) {
        try {
            serverSocket = new ServerSocket(port);
            fileServerDir = pathToDir;
        } catch (IOException e) {
            System.err.println("Could not start server: " + e);
            System.exit(-1);
        }
        System.out.println("FileServer accepting connections on port " + port);

        // request handler loop
        new Thread(() -> {
            while (!serverSocket.isClosed()) {
                Socket socket = null;
                try {
                    // wait for request
                    socket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    PrintStream pout = new PrintStream(out);

                    // read first line of request (ignore the rest)
                    String request = in.readLine();
                    if (request == null)
                        continue;
                    log(socket, request);
                    while (true) {
                        String misc = in.readLine();
                        if (misc == null || misc.length() == 0)
                            break;
                    }

                    // parse the line
                    if (!(request.endsWith("HTTP/1.0") || request.endsWith("HTTP/1.1"))) {
                        // bad request
                        errorReport(pout, socket, "400", "Bad Request",
                                "Your browser sent a request that " +
                                        "this server could not understand.");
                    } else {
                        if (request.substring(0, 4).equals("POST")) {
                            InputStream is = socket.getInputStream();
                            receiveFile(is);
                        } else {
                            String req = request.substring(4, request.length() - 9).trim();
                            if (req.indexOf("..") != -1 ||
                                    req.indexOf("/.ht") != -1 || req.endsWith("~")) {
                                // evil hacker trying to read non-wwwhome or secret file
                                errorReport(pout, socket, "403", "Forbidden",
                                        "You don't have permission to access the requested URL.");
                            } else {
                                String path = pathToDir + "/" + req;
                                File f = new File(path);
                                if (f.isDirectory() && !path.endsWith("/")) {
                                    // redirect browser if referring to directory without final '/'
                                    pout.print("HTTP/1.0 301 Moved Permanently\r\n" +
                                            "Location: http://" +
                                            socket.getLocalAddress().getHostAddress() + ":" +
                                            socket.getLocalPort() + "/" + req + "/\r\n\r\n");
                                    log(socket, "301 Moved Permanently");
                                } else {
                                    if (f.isDirectory()) {
                                        // if directory, implicitly add 'index.html'
                                        path = path + "index.html";
                                        f = new File(path);
                                    }
                                    try {
                                        // send file
                                        InputStream file = new FileInputStream(f);
                                        pout.print("HTTP/1.0 200 OK\r\n" +
                                                "Content-Type: " + guessContentType(path) + "\r\n" +
                                                "Date: " + new Date() + "\r\n" +
                                                "Server: FileServer 1.0\r\n\r\n");
                                        sendFile(file, out); // send raw file
                                        log(socket, "200 OK");
                                    } catch (FileNotFoundException e) {
                                        // file not found
                                        errorReport(pout, socket, "404", "Not Found",
                                                "The requested URL was not found on this server.");
                                    }
                                }
                            }
                        }
                    }
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    System.err.println(e);
                }
                try {
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                    System.err.println(e);
                }
            }
        }).start();
    }

    private static void log(Socket connection, String msg) {
        System.err.println(new Date() + " [" + connection.getInetAddress().getHostAddress() +
                ":" + connection.getPort() + "] " + msg);
    }

    private static void errorReport(PrintStream pout, Socket connection, String code, String title, String msg) {
        pout.print("HTTP/1.0 " + code + " " + title + "\r\n" +
                "\r\n" +
                "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\r\n" +
                "<TITLE>" + code + " " + title + "</TITLE>\r\n" +
                "</HEAD><BODY>\r\n" +
                "<H1>" + title + "</H1>\r\n" + msg + "<P>\r\n" +
                "<HR><ADDRESS>FileServer 1.0 at " +
                connection.getLocalAddress().getHostName() +
                " Port " + connection.getLocalPort() + "</ADDRESS>\r\n" +
                "</BODY></HTML>\r\n");
        log(connection, code + " " + title);
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm"))
            return "text/html";
        else if (path.endsWith(".txt") || path.endsWith(".java"))
            return "text/plain";
        else if (path.endsWith(".gif"))
            return "image/gif";
        else if (path.endsWith(".class"))
            return "application/octet-stream";
        else if (path.endsWith(".jpg") || path.endsWith(".jpeg"))
            return "image/jpeg";
        else
            return "text/plain";
    }

    private static void sendFile(InputStream file, OutputStream out) {
        try {
            byte[] buffer = new byte[1000];
            while (file.available() > 0)
                out.write(buffer, 0, file.read(buffer));
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    private static void receiveFile(InputStream is) {
        try {
            int bytesRead;
            String fileName = "some_name.zip";
            OutputStream output = new FileOutputStream(fileName);
            byte[] buffer = new byte[1024];
            while ((bytesRead = is.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            // Closing the FileOutputStream handle
            is.close();
            output.close();
            System.out.println("File " + fileName + " received from client.");
        } catch (IOException ex) {
            System.err.println("Client error. Connection closed.");
        }
    }
}