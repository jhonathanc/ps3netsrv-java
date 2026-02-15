package com.jhonju.ps3netsrv;

import com.jhonju.ps3netsrv.server.PS3NetSrvTask;
import com.jhonju.ps3netsrv.server.enums.EListType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PS3NetSrvMain {

    public static void main(String[] args) {
        System.out.println("ps3netsrv-java version 0.4 Alpha");
        Map<String, String> options = new HashMap<>();
        options.put("-F", System.getProperty("user.dir"));
        options.put("-I", "");
        options.put("-M", "0");
        options.put("-P", "38008");
        options.put("-R", "false");
        options.put("-T", "NONE");

        for (int i = 0; i < args.length; i += 2) {
            if (args[i].equals("-H")) {
                printHelp();
                System.exit(0);
            }
            if (i + 1 >= args.length) {
                System.err.println("Invalid option: " + args[i]);
                System.exit(1);
            }
            options.put(args[i], args[i + 1]);
        }

        String folderPath = options.get("-F");
        int port = Integer.parseInt(options.get("-P"));
        int maxConnections = Integer.parseInt(options.get("-M"));
        boolean readOnly = Boolean.parseBoolean(options.get("-R"));
        EListType listType = EListType.valueOf("LIST_TYPE_" + options.get("-T"));

        Set<String> filterAddresses = new HashSet<>();
        String[] filterAddressArray = options.get("-I").split(",");
        filterAddresses.addAll(Arrays.asList(filterAddressArray));

        System.out.println("Server is running at " + port);
        System.out.println("Server is running at " + folderPath);

        PS3NetSrvTask server = new PS3NetSrvTask(port, folderPath, maxConnections, readOnly, filterAddresses, listType,
                (thread, throwable) -> System.err
                        .println((thread != null ? thread.getId() : "Unknown") + " " + throwable.getMessage()));

        server.run();
        System.out.println("Server end");
    }

    private static void printHelp() {
        System.out.println("Usage: ps3netsrv [OPTIONS]");
        System.out.println("Options:");
        System.out.println("  -F <path>      Folder path (default: current directory)");
        System.out.println("  -I <address>   Filter address (separate multiple ips with comma)");
        System.out.println("  -M <number>    Max. allowed connections (default: 0)");
        System.out.println("  -P <number>    Port (default: 38008)");
        System.out.println("  -R <true|false> Read only (default: false)");
        System.out.println("  -T <ALLOWED|BLOCKED|NONE>  List type (default: NONE)");
        System.out.println("  -H             Show this help message and exit");
    }
}
