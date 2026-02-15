package com.jhonju.ps3netsrv.server.io;

import com.jhonju.ps3netsrv.server.charset.StandardCharsets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.Locale;

public class VirtualIsoFile implements IFile {

    private static final int SECTOR_SIZE = 2048;
    private static final int MAX_DIRECTORY_BUFFER_SIZE = 4 * 1024 * 1024; // 4MB to handle large directories
    private static final int PATH_TABLE_ENTRY_ESTIMATE = 32;

    // Multi-extent support - max size per extent (~4GB, sector-aligned)
    private static final long MULTIEXTENT_PART_SIZE = 0xFFFFF800L;

    // ISO 9660 file flags
    private static final byte ISO_FILE = 0x00;
    private static final byte ISO_DIRECTORY = 0x02;
    private static final byte ISO_MULTIEXTENT = (byte) 0x80;

    // Multipart file pattern (.66600, .66601, etc.)
    private static final String MULTIPART_SUFFIX_PATTERN = ".66600";

    private final IFile rootFile;
    private final String volumeName;

    // PS3 Mode fields
    private final boolean ps3Mode;
    private final String titleId;

    private ByteBuffer fsBuf;
    private int fsBufSize;
    private long totalSize;

    private DirList rootList;
    private List<FileEntry> allFiles;

    // Lock object for thread-safe access to fsBuf
    private final Object fsBufLock = new Object();

    private static class FileEntry {
        String name;
        long size;
        int rlba;
        long startOffset;
        long endOffset;

        // Multipart support - list of file parts
        List<IFile> fileParts = new ArrayList<>();
        boolean isMultipart;

        // Multi-extent support - number of extent parts for files > 4GB
        int extentParts = 1;
    }

    private static class DirList {
        String name;
        DirList parent;
        int idx;
        int lba;
        int sizeBytes;
        byte[] content;
        List<FileEntry> files = new ArrayList<>();
    }

    public VirtualIsoFile(IFile rootDir) throws IOException {
        this.rootFile = rootDir;

        // Detect PS3 mode by checking for PS3_GAME/PARAM.SFO
        String detectedTitleId = ParamSfoParser.getTitleId(rootDir);
        this.ps3Mode = (detectedTitleId != null);
        this.titleId = detectedTitleId;

        if (ps3Mode) {
            this.volumeName = "PS3VOLUME";
        } else {
            this.volumeName = rootDir.getName() != null ? rootDir.getName().toUpperCase() : "DVDVIDEO";
        }

        build();
    }

    private void build() throws IOException {
        allFiles = new ArrayList<>();
        rootList = new DirList();
        rootList.name = "";
        rootList.parent = rootList;
        rootList.idx = 1;

        List<DirList> allDirs = new ArrayList<>();
        allDirs.add(rootList);

        scanDirectory(rootFile, rootList, allDirs);

        // 1.5. Map parents to children for DFS/BFS traversals
        Map<DirList, List<DirList>> childrenMap = new HashMap<>();
        for (DirList dir : allDirs) {
            if (dir.parent != null && dir != rootList) {
                List<DirList> children = childrenMap.computeIfAbsent(dir.parent, k -> new ArrayList<>());
                children.add(dir);
            }
        }

        // 2. Calculate sizes and assign Relative LBAs for files
        int currentFileSectorOffset = 0;
        allFiles = new ArrayList<>();
        currentFileSectorOffset = scanFilesDFS(rootList, currentFileSectorOffset, allFiles, childrenMap);

        int filesSizeSectors = currentFileSectorOffset;

        // 4. Generate Path Tables - perform BFS to rebuild allDirs in correct order
        List<DirList> sortedDirs = new ArrayList<>();
        List<DirList> queue = new ArrayList<>();
        queue.add(rootList);
        rootList.idx = 1;
        sortedDirs.add(rootList);

        int queuePtr = 0;
        while (queuePtr < queue.size()) {
            DirList parent = queue.get(queuePtr++);
            List<DirList> children = childrenMap.get(parent);
            if (children != null) {
                // Sort siblings by name
                children.sort(Comparator.comparing(o -> o.name.toUpperCase(Locale.US)));

                for (DirList child : children) {
                    child.idx = sortedDirs.size() + 1;
                    sortedDirs.add(child);
                    queue.add(child);
                }
            }
        }

        allDirs.clear();
        allDirs.addAll(sortedDirs);

        byte[] pathTableL = generatePathTable(allDirs, false);
        int pathTableSize = pathTableL.length;
        int pathTableSectors = (pathTableSize + SECTOR_SIZE - 1) / SECTOR_SIZE;

        // 5. Calculate Layout
        int lba = 20; // Force Path Table L at LBA 20
        int pathTableL_LBA = lba;
        lba += pathTableSectors;

        int pathTableM_LBA = lba;
        lba += pathTableSectors;

        if (lba < 32) {
            lba = 32;
        }

        for (DirList dir : allDirs) {
            dir.lba = lba;
            byte[] content = generateDirectoryContent(dir, allDirs, 0);
            dir.content = content;
            dir.sizeBytes = content.length;
            int sectors = (content.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
            lba += sectors;
        }

        int filesStartLba = lba;

        // 7. Fixup Directory Records
        for (DirList dir : allDirs) {
            dir.content = generateDirectoryContent(dir, allDirs, filesStartLba);
        }

        int volumeSize = filesStartLba + filesSizeSectors;
        int padSectors = 0x20;
        if ((volumeSize & 0x1F) != 0) {
            padSectors += (0x20 - (volumeSize & 0x1F));
        }

        int finalVolumeSize = volumeSize + padSectors;
        if ((finalVolumeSize & 0x1F) != 0) {
            finalVolumeSize = (finalVolumeSize + 0x1F) & ~0x1F;
        }

        // 7. Build fsBuf
        fsBufSize = filesStartLba * SECTOR_SIZE;
        fsBuf = ByteBuffer.allocate(fsBufSize);
        fsBuf.order(ByteOrder.LITTLE_ENDIAN);
        Arrays.fill(fsBuf.array(), (byte) 0);

        if (ps3Mode) {
            writePS3Sectors(fsBuf, finalVolumeSize);
        }

        writePVD(fsBuf, 16 * SECTOR_SIZE, pathTableSize, pathTableL_LBA, pathTableM_LBA, rootList, finalVolumeSize);

        // Terminator
        fsBuf.put(17 * SECTOR_SIZE, (byte) 255);
        byte[] cd001 = "CD001".getBytes(StandardCharsets.US_ASCII);
        fsBuf.position(17 * SECTOR_SIZE + 1);
        fsBuf.put(cd001);
        fsBuf.put((byte) 1);

        pathTableL = generatePathTable(allDirs, false);
        byte[] pathTableM = generatePathTable(allDirs, true);

        fsBuf.position(pathTableL_LBA * SECTOR_SIZE);
        fsBuf.put(pathTableL);

        fsBuf.position(pathTableM_LBA * SECTOR_SIZE);
        fsBuf.put(pathTableM);

        for (DirList dir : allDirs) {
            fsBuf.position(dir.lba * SECTOR_SIZE);
            fsBuf.put(dir.content);
        }

        totalSize = (long) finalVolumeSize * SECTOR_SIZE;

        long filesAreaStartOffset = (long) filesStartLba * SECTOR_SIZE;
        for (FileEntry f : allFiles) {
            f.startOffset = filesAreaStartOffset + ((long) f.rlba * SECTOR_SIZE);
            f.endOffset = f.startOffset + f.size;
        }
    }

    private void writePS3Sectors(ByteBuffer bb, int volumeSizeSectors) {
        bb.position(0);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(1); // numRanges
        bb.putInt(0); // zero
        bb.putInt(0); // startSector
        bb.putInt(volumeSizeSectors - 1); // endSector

        bb.position(SECTOR_SIZE);
        byte[] consoleId = "PlayStation3".getBytes(StandardCharsets.US_ASCII);
        bb.put(consoleId);
        for (int i = consoleId.length; i < 0x10; i++) {
            bb.put((byte) 0);
        }

        byte[] productId = new byte[0x20];
        Arrays.fill(productId, (byte) ' ');
        if (titleId != null && titleId.length() >= 9) {
            byte[] tid = titleId.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tid, 0, productId, 0, Math.min(4, tid.length));
            productId[4] = '-';
            if (tid.length > 4) {
                System.arraycopy(tid, 4, productId, 5, Math.min(tid.length - 4, 5));
            }
        }
        bb.put(productId);

        for (int i = 0; i < 0x10; i++) {
            bb.put((byte) 0);
        }

        byte[] info = new byte[0x1B0];
        new SecureRandom().nextBytes(info);
        bb.put(info);

        byte[] hash = new byte[0x10];
        new SecureRandom().nextBytes(hash);
        bb.put(hash);

        bb.order(ByteOrder.LITTLE_ENDIAN);
    }

    private void scanDirectory(IFile dir, DirList dirEntry, List<DirList> allDirs) throws IOException {
        IFile[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        Arrays.sort(files, Comparator.comparing(o -> o.getName().toUpperCase(Locale.US)));

        List<String> processedMultiparts = new ArrayList<>();

        for (IFile f : files) {
            String name = f.getName();
            if (name == null)
                continue;

            if (f.isDirectory()) {
                DirList child = new DirList();
                child.name = name;
                child.parent = dirEntry;
                allDirs.add(child);
                scanDirectory(f, child, allDirs);
            } else {
                if (isMultipartFile(name)) {
                    if (!name.endsWith(MULTIPART_SUFFIX_PATTERN)) {
                        continue;
                    }

                    String baseName = name.substring(0, name.length() - 6);
                    if (processedMultiparts.contains(baseName)) {
                        continue;
                    }
                    processedMultiparts.add(baseName);

                    FileEntry fe = createMultipartFileEntry(dir, baseName, f);
                    if (fe != null) {
                        dirEntry.files.add(fe);
                    }
                } else {
                    FileEntry fe = new FileEntry();
                    fe.name = name;
                    fe.size = f.length();
                    fe.fileParts.add(f);
                    fe.isMultipart = false;
                    dirEntry.files.add(fe);
                }
            }
        }
    }

    private boolean isMultipartFile(String name) {
        if (name == null || name.length() < 7) {
            return false;
        }
        int dotPos = name.length() - 6;
        if (name.charAt(dotPos) != '.' ||
                name.charAt(dotPos + 1) != '6' ||
                name.charAt(dotPos + 2) != '6' ||
                name.charAt(dotPos + 3) != '6') {
            return false;
        }
        char d1 = name.charAt(dotPos + 4);
        char d2 = name.charAt(dotPos + 5);
        return Character.isDigit(d1) && Character.isDigit(d2);
    }

    private FileEntry createMultipartFileEntry(IFile dir, String baseName, IFile firstPart) throws IOException {
        FileEntry fe = new FileEntry();
        fe.name = baseName;
        fe.isMultipart = true;
        fe.size = 0;

        fe.fileParts.add(firstPart);
        fe.size += firstPart.length();

        for (int i = 1; i < 100; i++) {
            String partName = baseName + String.format(".666%02d", i);
            IFile part = dir.findFile(partName);
            if (part == null || !part.exists() || !part.isFile()) {
                break;
            }
            fe.fileParts.add(part);
            fe.size += part.length();
        }

        return fe;
    }

    private byte[] generatePathTable(List<DirList> dirs, boolean msb) {
        ByteBuffer bb = ByteBuffer.allocate(dirs.size() * PATH_TABLE_ENTRY_ESTIMATE + 1024);
        bb.order(msb ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

        for (DirList d : dirs) {
            String name = d.name;
            if (d == rootList)
                name = "\0";
            else
                name = name.toUpperCase(Locale.US);

            int len_di = (d == rootList) ? 1 : name.length();

            bb.put((byte) len_di);
            bb.put((byte) 0);
            bb.putInt(d.lba);

            short parentIdx = (short) ((d == rootList) ? 1 : d.parent.idx);
            bb.putShort(parentIdx);

            if (d == rootList)
                bb.put((byte) 0);
            else
                bb.put(name.getBytes(StandardCharsets.US_ASCII));

            if (len_di % 2 != 0)
                bb.put((byte) 0);
        }
        byte[] res = new byte[bb.position()];
        bb.position(0);
        bb.get(res);
        return res;
    }

    private byte[] generateDirectoryContent(DirList dir, List<DirList> allDirs, int filesStartLba) {
        ByteBuffer bb = ByteBuffer.allocate(MAX_DIRECTORY_BUFFER_SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        writeDirRecord(bb, dir, ".", ISO_DIRECTORY);
        writeDirRecord(bb, dir.parent, "..", ISO_DIRECTORY);

        List<Object> allEntries = new ArrayList<>(dir.files);
        for (DirList d : allDirs) {
            if (d.parent == dir && d != dir && d != rootList)
                allEntries.add(d);
        }

        allEntries.sort((o1, o2) -> {
            String n1 = (o1 instanceof FileEntry) ? ((FileEntry) o1).name : ((DirList) o1).name;
            String n2 = (o2 instanceof FileEntry) ? ((FileEntry) o2).name : ((DirList) o2).name;
            return n1.toUpperCase(Locale.US).compareTo(n2.toUpperCase(Locale.US));
        });

        for (Object o : allEntries) {
            if (o instanceof FileEntry) {
                writeFileRecord(bb, (FileEntry) o, filesStartLba);
            } else {
                DirList sub = (DirList) o;
                writeDirRecord(bb, sub, sub.name, ISO_DIRECTORY);
            }
        }

        int remainder = bb.position() % SECTOR_SIZE;
        if (remainder != 0) {
            int pad = SECTOR_SIZE - remainder;
            for (int i = 0; i < pad; i++)
                bb.put((byte) 0);
        }

        byte[] res = new byte[bb.position()];
        bb.position(0);
        bb.get(res);
        return res;
    }

    private int scanFilesDFS(DirList dir, int currentSectorOffset, List<FileEntry> fileList,
            Map<DirList, List<DirList>> childrenMap) {
        List<FileEntry> sortedFiles = new ArrayList<>(dir.files);
        sortedFiles.sort(Comparator.comparing(o -> o.name.toUpperCase(Locale.US)));

        for (FileEntry file : sortedFiles) {
            file.rlba = currentSectorOffset;
            int sectors = (int) ((file.size + SECTOR_SIZE - 1) / SECTOR_SIZE);
            currentSectorOffset += sectors;
            fileList.add(file);

            if (file.size > MULTIEXTENT_PART_SIZE) {
                file.extentParts = (int) ((file.size + MULTIEXTENT_PART_SIZE - 1) / MULTIEXTENT_PART_SIZE);
            }
        }

        List<DirList> children = childrenMap.get(dir);
        if (children != null) {
            List<DirList> sortedChildren = new ArrayList<>(children);
            sortedChildren.sort(Comparator.comparing(o -> o.name.toUpperCase(Locale.US)));
            for (DirList child : sortedChildren) {
                currentSectorOffset = scanFilesDFS(child, currentSectorOffset, fileList, childrenMap);
            }
        }
        return currentSectorOffset;
    }

    private void writeDirRecord(ByteBuffer bb, DirList target, String name, int flags) {
        int nameLen = (name.equals(".") || name.equals("..")) ? 1 : name.length();
        int padOverhead = 6;
        int recordLen = 33 + nameLen + padOverhead;
        if (recordLen % 2 != 0)
            recordLen++;

        int pos = bb.position();
        if ((pos % SECTOR_SIZE) + recordLen > SECTOR_SIZE) {
            int pad = (((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE) - pos;
            for (int i = 0; i < pad; i++)
                bb.put((byte) 0);
        }

        bb.put((byte) recordLen);
        bb.put((byte) 0);
        putBothEndianInt(bb, target.lba);
        putBothEndianInt(bb, target.sizeBytes);
        putDate(bb);
        bb.put((byte) flags);
        bb.put((byte) 0);
        bb.put((byte) 0);
        putBothEndianShort(bb, (short) 1);
        bb.put((byte) nameLen);

        if (name.equals("."))
            bb.put((byte) 0);
        else if (name.equals(".."))
            bb.put((byte) 1);
        else
            bb.put(name.toUpperCase(Locale.US).getBytes(StandardCharsets.US_ASCII));

        int bytesWritten = 33 + nameLen;
        while (bytesWritten < recordLen) {
            bb.put((byte) 0);
            bytesWritten++;
        }
    }

    private void writeFileRecord(ByteBuffer bb, FileEntry f, int filesStartLba) {
        if (f.extentParts > 1) {
            writeMultiExtentFileRecords(bb, f, filesStartLba);
            return;
        }

        String name = f.name;
        int nameLen = name.length();
        int nameLenWithSuffix = nameLen + 2; // +2 for ";1"
        int padOverhead = 6;
        int recordLen = 33 + nameLenWithSuffix + padOverhead;
        if (recordLen % 2 != 0)
            recordLen++;

        int pos = bb.position();
        if ((pos % SECTOR_SIZE) + recordLen > SECTOR_SIZE) {
            int pad = (((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE) - pos;
            for (int i = 0; i < pad; i++)
                bb.put((byte) 0);
        }

        bb.put((byte) recordLen);
        bb.put((byte) 0);
        putBothEndianInt(bb, filesStartLba + f.rlba);
        putBothEndianInt(bb, (int) f.size);
        putDate(bb);
        bb.put(ISO_FILE);
        bb.put((byte) 0);
        bb.put((byte) 0);
        putBothEndianShort(bb, (short) 1);
        bb.put((byte) nameLenWithSuffix);
        bb.put(name.toUpperCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
        bb.put((byte) ';');
        bb.put((byte) '1');

        int bytesWritten = 33 + nameLenWithSuffix;
        while (bytesWritten < recordLen) {
            bb.put((byte) 0);
            bytesWritten++;
        }
    }

    private void writeMultiExtentFileRecords(ByteBuffer bb, FileEntry f, int filesStartLba) {
        String name = f.name;
        int nameLenWithSuffix = name.length() + 2;
        int padOverhead = 6;
        int recordLen = 33 + nameLenWithSuffix + padOverhead;
        if (recordLen % 2 != 0)
            recordLen++;

        int lba = filesStartLba + f.rlba;
        long remainingSize = f.size;

        for (int part = 0; part < f.extentParts; part++) {
            long currentSize = Math.min(remainingSize, MULTIEXTENT_PART_SIZE);
            int pos = bb.position();
            if ((pos % SECTOR_SIZE) + recordLen > SECTOR_SIZE) {
                int pad = (((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE) - pos;
                for (int i = 0; i < pad; i++)
                    bb.put((byte) 0);
            }

            bb.put((byte) recordLen);
            bb.put((byte) 0);
            putBothEndianInt(bb, lba);
            putBothEndianInt(bb, (int) currentSize);
            putDate(bb);

            byte flags = ISO_FILE;
            if (part < f.extentParts - 1)
                flags |= ISO_MULTIEXTENT;

            bb.put(flags);
            bb.put((byte) 0);
            bb.put((byte) 0);
            putBothEndianShort(bb, (short) 1);
            bb.put((byte) nameLenWithSuffix);
            bb.put(name.toUpperCase(Locale.US).getBytes(StandardCharsets.US_ASCII));
            bb.put((byte) ';');
            bb.put((byte) '1');

            int bytesWritten = 33 + nameLenWithSuffix;
            while (bytesWritten < recordLen) {
                bb.put((byte) 0);
                bytesWritten++;
            }

            lba += (int) ((currentSize + SECTOR_SIZE - 1) / SECTOR_SIZE);
            remainingSize -= currentSize;
        }
    }

    private void putBothEndianInt(ByteBuffer bb, int val) {
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(val);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(val);
        bb.order(ByteOrder.LITTLE_ENDIAN);
    }

    private void putBothEndianShort(ByteBuffer bb, short val) {
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(val);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putShort(val);
        bb.order(ByteOrder.LITTLE_ENDIAN);
    }

    private void putDate(ByteBuffer bb) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        bb.put((byte) (c.get(Calendar.YEAR) - 1900));
        bb.put((byte) (c.get(Calendar.MONTH) + 1));
        bb.put((byte) c.get(Calendar.DAY_OF_MONTH));
        bb.put((byte) c.get(Calendar.HOUR_OF_DAY));
        bb.put((byte) c.get(Calendar.MINUTE));
        bb.put((byte) c.get(Calendar.SECOND));
        bb.put((byte) 0);
    }

    private void writePVD(ByteBuffer bb, int offset, int pathTableSize, int ptL, int ptM, DirList root,
            int volumeSizeSectors) {
        bb.position(offset);
        bb.put((byte) 1);
        bb.put("CD001".getBytes(StandardCharsets.US_ASCII));
        bb.put((byte) 1);
        bb.put((byte) 0);
        pad(bb, 32);
        byte[] volBytes = volumeName.getBytes(StandardCharsets.US_ASCII);
        bb.put(volBytes, 0, Math.min(volBytes.length, 32));
        pad(bb, 32 - Math.min(volBytes.length, 32));
        pad(bb, 8);
        putBothEndianInt(bb, volumeSizeSectors);
        pad(bb, 32);
        putBothEndianShort(bb, (short) 1);
        putBothEndianShort(bb, (short) 1);
        putBothEndianShort(bb, (short) SECTOR_SIZE);
        putBothEndianInt(bb, pathTableSize);
        bb.putInt(ptL);
        bb.putInt(0);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(ptM);
        bb.putInt(0);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        ByteBuffer temp = ByteBuffer.allocate(SECTOR_SIZE);
        temp.order(ByteOrder.LITTLE_ENDIAN);
        writeDirRecord(temp, root, ".", ISO_DIRECTORY);
        byte[] rootRecord = new byte[temp.position()];
        temp.position(0);
        temp.get(rootRecord);
        bb.put(rootRecord);

        pad(bb, SECTOR_SIZE - (bb.position() - offset));
    }

    private void pad(ByteBuffer bb, int count) {
        for (int i = 0; i < count; i++)
            bb.put((byte) 0);
    }

    private int findFileEntryIndex(long position) {
        if (allFiles == null || allFiles.isEmpty())
            return -1;
        int low = 0;
        int high = allFiles.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            FileEntry entry = allFiles.get(mid);
            long fileAreaEnd = entry.startOffset + ((entry.size + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE;
            if (position < entry.startOffset)
                high = mid - 1;
            else if (position >= fileAreaEnd)
                low = mid + 1;
            else
                return mid;
        }
        return -1;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isFile() {
        return true;
    }

    @Override
    public boolean isDirectory() {
        return false;
    }

    @Override
    public boolean delete() {
        return false;
    }

    @Override
    public long length() {
        return totalSize;
    }

    @Override
    public IFile[] listFiles() {
        return null;
    }

    @Override
    public long lastModified() {
        return rootFile.lastModified();
    }

    @Override
    public String getName() {
        return rootFile.getName() + ".iso";
    }

    @Override
    public String[] list() {
        return null;
    }

    @Override
    public IFile findFile(String fileName) {
        return null;
    }

    @Override
    public int read(byte[] buffer, long position) throws IOException {
        return read(buffer, 0, buffer.length, position);
    }

    @Override
    public int read(byte[] buffer, int offset, int length, long position) throws IOException {
        long remaining = length;
        int r = 0;
        int bufOffset = offset;
        if (position >= totalSize)
            return 0;

        if (position < fsBufSize) {
            int toRead = (int) Math.min(fsBufSize - position, remaining);
            synchronized (fsBufLock) {
                fsBuf.position((int) position);
                fsBuf.get(buffer, bufOffset, toRead);
            }
            remaining -= toRead;
            r += toRead;
            bufOffset += toRead;
            position += toRead;
        }

        while (remaining > 0 && position < totalSize) {
            int fileIdx = findFileEntryIndex(position);
            if (fileIdx < 0) {
                int toRead = (int) Math.min(totalSize - position, remaining);
                Arrays.fill(buffer, bufOffset, bufOffset + toRead, (byte) 0);
                r += toRead;
                break;
            }

            FileEntry f = allFiles.get(fileIdx);
            long fileAreaEnd = f.startOffset + ((f.size + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE;

            if (position < f.endOffset) {
                long offsetInFile = position - f.startOffset;
                int toRead = (int) Math.min(f.endOffset - position, remaining);
                int readCount;
                if (f.isMultipart) {
                    readCount = readFromMultipartFile(f, offsetInFile, buffer, bufOffset, toRead);
                } else {
                    readCount = f.fileParts.get(0).read(buffer, bufOffset, toRead, offsetInFile);
                }
                if (readCount > 0) {
                    r += readCount;
                    remaining -= readCount;
                    bufOffset += readCount;
                    position += readCount;
                } else
                    break;
            }

            if (remaining > 0 && position >= f.endOffset && position < fileAreaEnd) {
                int pad = (int) Math.min(fileAreaEnd - position, remaining);
                Arrays.fill(buffer, bufOffset, bufOffset + pad, (byte) 0);
                r += pad;
                remaining -= pad;
                bufOffset += pad;
                position += pad;
            }
        }
        return r;
    }

    private int readFromMultipartFile(FileEntry f, long offsetInFile, byte[] buffer, int bufOffset, int toRead)
            throws IOException {
        int totalRead = 0;
        long currentOffset = offsetInFile;
        int currentBufOffset = bufOffset;
        int remainingToRead = toRead;

        long partStartOffset = 0;
        for (int partIdx = 0; partIdx < f.fileParts.size() && remainingToRead > 0; partIdx++) {
            IFile part = f.fileParts.get(partIdx);
            long partSize = part.length();
            long partEndOffset = partStartOffset + partSize;
            if (currentOffset >= partStartOffset && currentOffset < partEndOffset) {
                long offsetInPart = currentOffset - partStartOffset;
                int bytesToReadFromPart = (int) Math.min(partEndOffset - currentOffset, remainingToRead);
                int readCount = part.read(buffer, currentBufOffset, bytesToReadFromPart, offsetInPart);
                if (readCount > 0) {
                    totalRead += readCount;
                    currentOffset += readCount;
                    currentBufOffset += readCount;
                    remainingToRead -= readCount;
                } else
                    break;
            }
            partStartOffset = partEndOffset;
        }
        return totalRead;
    }

    @Override
    public void close() throws IOException {
        synchronized (fsBufLock) {
            fsBuf = null;
        }
        if (allFiles != null) {
            for (FileEntry f : allFiles) {
                if (f.fileParts != null) {
                    for (IFile part : f.fileParts) {
                        try {
                            part.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            allFiles = null;
        }
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        throw new IOException("ReadOnly");
    }

    @Override
    public boolean createDirectory(String name) {
        return false;
    }

    @Override
    public boolean createFile(String name) {
        return false;
    }

    @Override
    public boolean mkdir() {
        return false;
    }
}
