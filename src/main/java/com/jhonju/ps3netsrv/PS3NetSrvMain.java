package com.jhonju.ps3netsrv;

import com.jhonju.ps3netsrv.server.PS3NetSrvTask;
import com.jhonju.ps3netsrv.server.enums.EListType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PS3NetSrvMain {

    public static void main(String[] args) {
        int port = 38008;
        int maxConnections = 0;
        boolean readOnly = false;
        EListType listType = EListType.LIST_TYPE_NONE;
        Set<String> filterAddresses = new HashSet<>();
        String folderPath;

        switch (args.length) {
            case 6:
                filterAddresses.addAll(Arrays.asList(args[5].split(";")));
            case 5:
                char argListType = args[4].toCharArray()[1];
                listType = argListType == 'A' ? EListType.LIST_TYPE_ALLOWED : argListType == 'B' ? EListType.LIST_TYPE_BLOCKED : EListType.LIST_TYPE_NONE;
            case 4:
                readOnly = Byte.parseByte(args[3]) != 0;
            case 3:
                maxConnections = Integer.parseInt(args[2]);
            case 2:
                port = Integer.parseInt(args[1]);
            case 1:
                folderPath = args[0];
                break;
            default:
                folderPath = System.getProperty("user.dir");
                break;
        }
        System.out.println("Server is running at " + port);
        System.out.println("Server is running at " + folderPath);

        PS3NetSrvTask server = new PS3NetSrvTask(port, folderPath, maxConnections, readOnly, filterAddresses, listType, (thread, throwable) -> System.err.println(thread.getId() + " " + throwable.getMessage()));

        server.run();
        System.out.println("Server end");
    }
}
