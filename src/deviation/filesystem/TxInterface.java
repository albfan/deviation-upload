package deviation.filesystem;

import java.io.IOException;
import java.util.Iterator;

import de.waldheinz.fs.FileSystem;
import de.waldheinz.fs.FsDirectory;
import de.waldheinz.fs.FsDirectoryEntry;
import de.waldheinz.fs.fat.FatFileSystem;
import de.waldheinz.fs.fat.SuperFloppyFormatter;
import deviation.Dfu;
import deviation.DfuDevice;
import deviation.DfuInterface;
import deviation.FileInfo;
import deviation.Progress;
import deviation.Transmitter;
import deviation.TxInfo;
import deviation.filesystem.DevoFS.DevoFSFileSystem;

public class TxInterface {
    
    public enum FSStatus {NO_FS, ROOT_FS, MEDIA_FS, ROOT_AND_MEDIA_FS};

    DfuDevice dev;
    FlashIO rootBlockDev;
    FlashIO mediaBlockDev;
    private final int SECTOR_SIZE = 4096;
    private FileSystem rootFs;
    private FileSystem mediaFs;
    private DfuInterface rootIface;
    private DfuInterface mediaIface;
    private Transmitter model;
    //private Progress progress;
    
    public TxInterface(DfuDevice dev) {
        mediaBlockDev = null;
        rootBlockDev = null;
        this.dev = dev;
        //this.progress = progress;
		TxInfo txInfo = dev.getTxInfo();
		Transmitter tx = txInfo.type();
        this.model = tx;
    	if (tx == Transmitter.DEVO_UNKNOWN)
    		return;
        if (tx.hasMediaFS()) {
    		mediaIface = dev.SelectInterfaceByAddr(tx.getMediaSectorOffset() * SECTOR_SIZE);
        	mediaBlockDev = new FlashIO(dev, tx.getMediaSectorOffset() * SECTOR_SIZE, tx.isMediaInverted(), SECTOR_SIZE, null);
        	dev.close();
        }
		rootIface = dev.SelectInterfaceByAddr(tx.getRootSectorOffset() * SECTOR_SIZE);
        rootBlockDev = new FlashIO(dev, tx.getRootSectorOffset() * SECTOR_SIZE, tx.isRootInverted(), SECTOR_SIZE, null);
    	
    }
    public void setProgress(Progress progress) {
    	rootBlockDev.setProgress(progress);
    	if (model.hasMediaFS()) {
    		mediaBlockDev.setProgress(progress);
    	}
    }
    public DfuDevice getDevice() { return dev; }
    public boolean hasSeparateMediaDrive() { return mediaBlockDev == null ? false : true; }
    public void Format(FSStatus type) throws IOException {
        if (type == FSStatus.ROOT_FS || type == FSStatus.ROOT_AND_MEDIA_FS) {
        	dev.SelectInterface(rootIface);
        	//rootBlockDev.markAllCached();
        	if (model.getRootFSType() == FSType.FAT) {
        		rootFs = SuperFloppyFormatter.get(rootBlockDev).format();
        	} else {
        		rootFs = DevoFSFileSystem.format(rootBlockDev);
        	}
            mediaFs = rootFs;
        }
        if (type == FSStatus.MEDIA_FS || type == FSStatus.ROOT_AND_MEDIA_FS) {
            if (model.hasMediaFS()) {
            	dev.SelectInterface(mediaIface);
            	//mediaBlockDev.markAllCached();
            	if (model.getRootFSType() == FSType.FAT) {
            		mediaFs = SuperFloppyFormatter.get(mediaBlockDev).format();
            	} else {
            		mediaFs = DevoFSFileSystem.format(mediaBlockDev);
            	}
            }
        }
    }
    public void Init(FSStatus type) throws IOException {
        if (type == FSStatus.ROOT_FS || type == FSStatus.ROOT_AND_MEDIA_FS) {
        	dev.SelectInterface(rootIface);
        	if (model.getRootFSType() == FSType.FAT) {
        		rootFs = FatFileSystem.read(rootBlockDev, false);
        	} else {
        		rootFs = DevoFSFileSystem.read(rootBlockDev, false);
        	}
            mediaFs = rootFs;
        }
        if (type == FSStatus.MEDIA_FS || type == FSStatus.ROOT_AND_MEDIA_FS) {
            if (model.hasMediaFS()) {
            	dev.SelectInterface(mediaIface);
            	if (model.getRootFSType() == FSType.FAT) {
            		mediaFs = FatFileSystem.read(mediaBlockDev, false);
            	} else {
            		mediaFs = DevoFSFileSystem.read(mediaBlockDev, false);
            	}
            }
        }
    }
    public void readDir(String dirStr) {
        if (! dirStr.matches("/.*")) {
            dirStr = "/" + dirStr;
        }
        try {
        FileSystem fs = (dirStr.matches("(?i:/media.*)")) ? mediaFs : rootFs;
        if (fs == rootFs) {
        	dev.SelectInterface(rootIface);
        } else {
        	dev.SelectInterface(mediaIface);
        }
        String[] dirs = dirStr.split("/");
        FsDirectory dir = fs.getRoot();
        for (String subdir : dirs) {
            if (subdir.equals("")) {
               continue;
            }
            dir = dir.getEntry(subdir).getDirectory();
        }
        Iterator<FsDirectoryEntry> itr = dir.iterator();
        while(itr.hasNext()) {
            FsDirectoryEntry entry = itr.next();
            System.out.println(entry.getName());
        }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void copyFile(FileInfo file) {
    	FileSystem fs = (file.name().matches("(?i:media/.*)")) ? mediaFs : rootFs;
        if (fs == rootFs) {
        	dev.SelectInterface(rootIface);
        } else {
        	dev.SelectInterface(mediaIface);
        }
    	FSUtils.copyFile(fs,  file);
    }
    public void close() {
    	if (rootFs != null) {
    		try {
    			dev.SelectInterface(rootIface);
    			rootFs.close();
    			rootBlockDev.close();
    		} catch(Exception e) { e.printStackTrace(); }
    	}
    	if (mediaFs != null && mediaFs != rootFs) {
    		try {
    			dev.SelectInterface(mediaIface);
    			mediaFs.close();
    			mediaBlockDev.close();
    		} catch (Exception e) { e.printStackTrace(); }
    	}
    }

    public static byte[] invert(byte[] data) {
        int i;
        for(i = 0; i < data.length; i++) {
            int j = ~data[i]; 
            data[i] = (byte)(j&0xff);
        }
        return data;
    }
    private boolean DetectFS(FSStatus status) throws IOException {
    	DfuInterface iface;
    	FlashIO blkdev;
    	FSType type;
    	if (status == FSStatus.ROOT_FS) {
    		iface = rootIface;
    		blkdev = rootBlockDev;
    		type = model.getRootFSType();
    	} else if (status == FSStatus.MEDIA_FS) {
    		iface = mediaIface;
    		blkdev = mediaBlockDev;
    		type = model.getMediaFSType();
    	} else {
    		throw new IOException();
    	}
    	dev.SelectInterface(iface);
        if (dev.open() != 0) {
        	throw new IOException();
        }
        dev.claim_and_set();
        Dfu.setIdle(dev);
    	boolean ret = FSUtils.DetectFS(blkdev, type);
    	dev.close();
    	return ret;
    }
    public FSStatus getFSStatus() {
        boolean has_root = false;
        boolean has_media = false;
        FSStatus status = FSStatus.NO_FS;
        if (model == Transmitter.DEVO_UNKNOWN) {
            return status;
        }
    
        if (model.hasMediaFS()) {
        	try {
        		has_media = DetectFS(FSStatus.MEDIA_FS);
        	} catch (Exception e){
        		System.out.println("Error: Unable to open media device");
        		return FSStatus.NO_FS;
        	}
        }
       	try {
       		has_root = DetectFS(FSStatus.ROOT_FS);
       	} catch (Exception e){
       		System.out.println("Error: Unable to open root device");
       		return FSStatus.NO_FS;
       	}
        //IOUtil.writeFile("fatroot", fatRootBytes);
        if (has_media && has_root) {
            status = FSStatus.ROOT_AND_MEDIA_FS;
        } else if (has_media) {
            status = FSStatus.MEDIA_FS;
        } else if  (has_root) {
            status = FSStatus.ROOT_FS;
        }
        return status;
    }

}
