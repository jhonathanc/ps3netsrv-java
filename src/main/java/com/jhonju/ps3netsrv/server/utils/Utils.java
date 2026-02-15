package com.jhonju.ps3netsrv.server.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import com.jhonju.ps3netsrv.server.io.PS3RegionInfo;

public class Utils {

    public static final int SHORT_CAPACITY = 2;
    public static final int INT_CAPACITY = 4;
    public static final int LONG_CAPACITY = 8;
    public static final short SECTOR_SIZE = 2048;
    public static final String DOT_STR = ".";
    public static final String DKEY_EXT = ".dkey";
    public static final String PS3ISO_FOLDER_NAME = "PS3ISO";
    public static final String REDKEY_FOLDER_NAME = "REDKEY";
    public static final String ISO_EXTENSION = ".iso";
    public static final int _3K3Y_KEY_OFFSET = 0xF80;
    public static final int _3K3Y_WATERMARK_OFFSET = 0xF70;
    public static final int ENCRYPTION_KEY_SIZE = 16;

    // 3k3y watermarks: "Encrypted 3K BLD" and "Dncrypted 3K BLD"
    public static final byte[] _3K3Y_ENCRYPTED_WATERMARK = {
            0x45, 0x6E, 0x63, 0x72, 0x79, 0x70, 0x74, 0x65,
            0x64, 0x20, 0x33, 0x4B, 0x20, 0x42, 0x4C, 0x44
    };
    public static final byte[] _3K3Y_DECRYPTED_WATERMARK = {
            0x44, 0x6E, 0x63, 0x72, 0x79, 0x70, 0x74, 0x65,
            0x64, 0x20, 0x33, 0x4B, 0x20, 0x42, 0x4C, 0x44
    };

    // Keys for D1 to decryption key conversion
    public static final byte[] _3K3Y_D1_KEY = {
            0x38, 0x0B, (byte) 0xCF, 0x0B, 0x53, 0x45, 0x5B, 0x3C,
            0x78, 0x17, (byte) 0xAB, 0x4F, (byte) 0xA3, (byte) 0xBA, (byte) 0x90, (byte) 0xED
    };
    public static final byte[] _3K3Y_D1_IV = {
            0x69, 0x47, 0x47, 0x72, (byte) 0xAF, 0x6F, (byte) 0xDA, (byte) 0xB3,
            0x42, 0x74, 0x3A, (byte) 0xEF, (byte) 0xAA, 0x18, 0x62, (byte) 0x87
    };

    private static final String osName = System.getProperty("os.name");
    public static final boolean isWindows = osName.toLowerCase().startsWith("windows");
    public static final boolean isOSX = osName.toLowerCase().contains("os x");
    public static final boolean isSolaris = osName.toLowerCase().contains("sunos");

    public static byte[] charArrayToByteArray(char[] chars) {
        CharBuffer charBuffer = CharBuffer.wrap(chars);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        Arrays.fill(byteBuffer.array(), (byte) 0); // clear sensitive data
        return bytes;
    }

    public static byte[] longToBytesBE(final long value) {
        ByteBuffer bb = ByteBuffer.allocate(LONG_CAPACITY).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(value);
        return bb.array();
    }

    public static byte[] intToBytesBE(final int value) {
        ByteBuffer bb = ByteBuffer.allocate(INT_CAPACITY).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(value);
        return bb.array();
    }

    public static byte[] shortToBytesBE(final short value) {
        ByteBuffer bb = ByteBuffer.allocate(SHORT_CAPACITY).order(ByteOrder.BIG_ENDIAN);
        bb.putShort(value);
        return bb.array();
    }

    public static boolean isByteArrayEmpty(byte[] byteArray) {
        return (byteArray.length == 0 || Arrays.equals(byteArray, new byte[byteArray.length]));
    }

    public static ByteBuffer readCommandData(InputStream in, int size) throws IOException {
        byte[] data = new byte[size];
        if (in.read(data) < 0)
            return null;
        return ByteBuffer.wrap(data);
    }

    public static int bytesBEToInt(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static PS3RegionInfo[] getRegionInfos(byte[] sec0sec1) {
        int regionCount = bytesBEToInt(Arrays.copyOf(sec0sec1, 4)) * 2 - 1;
        PS3RegionInfo[] regionInfos = new PS3RegionInfo[regionCount];
        for (int i = 0; i < regionCount; ++i) {
            int offset = 12 + (i * INT_CAPACITY);
            long lastAddr = (bytesBEToInt(Arrays.copyOfRange(sec0sec1, offset, offset + INT_CAPACITY))
                    - (i % 2 == 1 ? 1L : 0L)) * SECTOR_SIZE + SECTOR_SIZE - 1L;
            regionInfos[i] = new PS3RegionInfo(i % 2 == 1, i == 0 ? 0L : regionInfos[i - 1].getLastAddress() + 1L,
                    lastAddr);
        }
        return regionInfos;
    }

    public static void decryptData(SecretKeySpec key, byte[] iv, byte[] data, int dataOffset, int sectorCount,
            long startLBA)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            for (int i = 0; i < sectorCount; ++i) {
                IvParameterSpec ivParams = new IvParameterSpec(resetIV(iv, startLBA + i));
                cipher.init(Cipher.DECRYPT_MODE, key, ivParams);
                int offset = dataOffset + (SECTOR_SIZE * i);
                byte[] decryptedSector = cipher.doFinal(data, offset, SECTOR_SIZE);
                System.arraycopy(decryptedSector, 0, data, offset, SECTOR_SIZE);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static byte[] resetIV(byte[] iv, long lba) {
        Arrays.fill(iv, (byte) 0);
        iv[12] = (byte) ((lba >>> 24) & 0xFF);
        iv[13] = (byte) ((lba >>> 16) & 0xFF);
        iv[14] = (byte) ((lba >>> 8) & 0xFF);
        iv[15] = (byte) (lba & 0xFF);
        return iv;
    }

    public static boolean has3K3YEncryptedWatermark(byte[] sec0sec1) {
        if (sec0sec1 == null || sec0sec1.length < _3K3Y_KEY_OFFSET + ENCRYPTION_KEY_SIZE) {
            return false;
        }
        for (int i = 0; i < ENCRYPTION_KEY_SIZE; i++) {
            if (sec0sec1[_3K3Y_WATERMARK_OFFSET + i] != _3K3Y_ENCRYPTED_WATERMARK[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean has3K3YDecryptedWatermark(byte[] sec0sec1) {
        if (sec0sec1 == null || sec0sec1.length < _3K3Y_WATERMARK_OFFSET + ENCRYPTION_KEY_SIZE) {
            return false;
        }
        for (int i = 0; i < ENCRYPTION_KEY_SIZE; i++) {
            if (sec0sec1[_3K3Y_WATERMARK_OFFSET + i] != _3K3Y_DECRYPTED_WATERMARK[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte[] convertD1ToKey(byte[] sec0sec1) throws IOException {
        if (sec0sec1 == null || sec0sec1.length < _3K3Y_KEY_OFFSET + ENCRYPTION_KEY_SIZE) {
            return null;
        }
        byte[] d1 = new byte[ENCRYPTION_KEY_SIZE];
        System.arraycopy(sec0sec1, _3K3Y_KEY_OFFSET, d1, 0, ENCRYPTION_KEY_SIZE);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(_3K3Y_D1_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(_3K3Y_D1_IV);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(d1);
        } catch (Exception e) {
            throw new IOException("Failed to convert D1 to key", e);
        }
    }

    private static Date parseOSXDate(Iterator<String> it) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd hh:mm:ss yyyy", Locale.getDefault());
        while (it.hasNext()) {
            String line = it.next();
            if (line != null) {
                String[] dateStr = line.replaceAll("\\s+", " ").split(" ");
                if (dateStr.length > 10) {
                    try {
                        return sdf.parse(dateStr[5] + " " + dateStr[6] + " " + dateStr[7] + " " + dateStr[8]);
                    } catch (Exception e) {
                        System.err.printf("/nCould not parse date %s", Arrays.toString(dateStr));
                    }
                }
            }
        }
        return null;
    }

    private enum FileStat {
        CREATION_DATE, ACCESS_DATE
    }

    private static Date getFileStatsWindows(String filePath, FileStat fileStat) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy hh:mm");
            String command = (fileStat == FileStat.CREATION_DATE ? "/TC" : "/TA");
            Process process = Runtime.getRuntime().exec(new String[] { "dir", command, filePath });
            process.waitFor();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] arr = line.split("\\s+");
                    String date = arr[0] + " " + arr[1];
                    try {
                        return sdf.parse(date);
                    } catch (ParseException ignored) {
                        System.err.printf("/nCould not parse date %s", date);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Date getFileStatsOSX(String filePath, FileStat fileStat) {
        try {
            String command = (fileStat == FileStat.CREATION_DATE ? "-laUT" : "-lauT");
            Process process = Runtime.getRuntime().exec(new String[] { "ls", command, filePath });
            process.waitFor();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                return parseOSXDate(lines.iterator());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long tryParseDate(String pattern, String value) {
        if (value != null) {
            try {
                return new SimpleDateFormat(pattern).parse(value).getTime();
            } catch (ParseException e) {
                System.err.printf("/nCould not parse date %s", value);
                return 0;
            }
        }
        System.err.println("Could not parse a null value");
        return 0;
    }

    public static long[] getFileStats(File file) {
        long[] stats = { 0, 0 };

        String filePath;
        try {
            filePath = file.getCanonicalPath();
        } catch (IOException e) {
            return stats;
        }

        if (isWindows) {
            Date creationDate = getFileStatsWindows(filePath, FileStat.CREATION_DATE);
            if (creationDate != null) {
                stats[0] = creationDate.getTime();
            }

            Date accessDate = getFileStatsWindows(filePath, FileStat.ACCESS_DATE);
            if (accessDate != null) {
                stats[1] = accessDate.getTime();
            }
        } else if (isOSX) {
            Date creationDate = getFileStatsOSX(filePath, FileStat.CREATION_DATE);
            if (creationDate != null) {
                stats[0] = creationDate.getTime();
            }

            Date accessDate = getFileStatsOSX(filePath, FileStat.ACCESS_DATE);
            if (accessDate != null) {
                stats[1] = accessDate.getTime();
            }
        } else if (isSolaris) {
            try {
                Process process = Runtime.getRuntime()
                        .exec(new String[] { "ls", "-E", filePath, "| grep 'crtime=' | sed 's/^.*crtime=//'" });
                process.waitFor();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                reader.close();
                if (line == null) {
                    System.err.println("Could not determine creation date for file: " + file.getName());
                } else {
                    stats[0] = tryParseDate("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", line);
                }
            } catch (Exception ignored) {
            }

            try {
                Process process = Runtime.getRuntime().exec(new String[] { "ls", "-lauE", filePath });
                process.waitFor();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                reader.close();
                if (line == null) {
                    System.err.println("Could not determine last access date for file: " + file.getName());
                } else {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 8) {
                        String month = parts[5];

                        Calendar cal = Calendar.getInstance();
                        cal.setTime(new Date());
                        int year = cal.get(Calendar.YEAR);
                        int actualMonth = cal.get(Calendar.MONTH);

                        cal.setTime(new SimpleDateFormat("MMM").parse(month));
                        if (cal.get(Calendar.MONTH) > actualMonth)
                            year--;

                        stats[1] = tryParseDate("MMM dd yyyy HH:mm",
                                month + " " + parts[6] + " " + year + " " + parts[7]);
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            stats[0] = file.lastModified();

            try {
                Process process = Runtime.getRuntime().exec(new String[] { "stat", "-c", "%x", filePath });
                process.waitFor();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        if (!line.contains("+"))
                            line = line.trim() + " +0000";
                        stats[1] = tryParseDate("yyyy-MM-dd HH:mm:ss.SSSSSSSSS Z", line);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return stats;
    }
}
