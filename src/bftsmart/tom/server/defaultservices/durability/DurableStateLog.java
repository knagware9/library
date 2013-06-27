package bftsmart.tom.server.defaultservices.durability;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import bftsmart.statemanagement.strategy.durability.CSTRequest;
import bftsmart.statemanagement.strategy.durability.CSTRequestF1;
import bftsmart.statemanagement.strategy.durability.CSTState;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.defaultservices.FileRecoverer;
import bftsmart.tom.server.defaultservices.StateLog;
import bftsmart.tom.util.TOMUtil;

public class DurableStateLog extends StateLog {

	private int id;
	public final static String DEFAULT_DIR = "files".concat(System
			.getProperty("file.separator"));
	private static final int INT_BYTE_SIZE = 4;
	private static final int EOF = 0;

	private RandomAccessFile log;
	private boolean syncLog;
	private String logPath;
	private String lastCkpPath;
	private boolean syncCkp;
	private boolean isToLog;
	private ReentrantLock checkpointLock = new ReentrantLock();
	private Map<Integer, Long> logPointers;
	
	public DurableStateLog(int id, byte[] initialState, byte[] initialHash,
			boolean isToLog, boolean syncLog, boolean syncCkp) {
		super(initialState, initialHash);
		this.id = id;
		this.isToLog = isToLog;
		this.syncLog = syncLog;
		this.syncCkp = syncCkp;
		this.logPointers = new HashMap<Integer, Long>();
		if (isToLog)
			createLogFile();
	}

	private void createLogFile() {
		logPath = DEFAULT_DIR + String.valueOf(id) + "."
				+ System.currentTimeMillis() + ".log";
		try {
			log = new RandomAccessFile(logPath, (syncLog ? "rwd" : "rw"));
			// PreAllocation
			/*
			 * log.setLength(TEN_MB); log.seek(0);
			 */
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Adds a message batch to the log. This batches should be added to the log
	 * in the same order in which they are delivered to the application. Only
	 * the 'k' batches received after the last checkpoint are supposed to be
	 * kept
	 * 
	 * @param batch
	 *            The batch of messages to be kept.
	 */
	public void addMessageBatch(byte[][] commands, int round, int leader) {
		CommandsInfo command = new CommandsInfo(commands, round, leader);
		if (isToLog)
			writeCommandToDisk(command);
	}

	private void writeCommandToDisk(CommandsInfo commandsInfo) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			ObjectOutputStream oos = new ObjectOutputStream(bos);
			oos.writeObject(commandsInfo);
			oos.flush();

			byte[] batchBytes = bos.toByteArray();

			ByteBuffer bf = ByteBuffer.allocate(2 * INT_BYTE_SIZE
					+ batchBytes.length);
			bf.putInt(batchBytes.length);
			bf.put(batchBytes);
			bf.putInt(EOF);
			
			log.write(bf.array());
			log.seek(log.length() - INT_BYTE_SIZE);// Next write will overwrite
													// the EOF mark
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
	    }
	}

	public void newCheckpoint(byte[] state, byte[] stateHash) {
		String ckpPath = DEFAULT_DIR + String.valueOf(id) + "."
				+ System.currentTimeMillis() + ".tmp";
		try {
			checkpointLock.lock();
			RandomAccessFile ckp = new RandomAccessFile(ckpPath,
					(syncCkp ? "rwd" : "rw"));

			ByteBuffer bf = ByteBuffer.allocate(state.length + stateHash.length
					+ 3 * INT_BYTE_SIZE);
			bf.putInt(state.length);
			bf.put(state);
			bf.putInt(stateHash.length);
			bf.put(stateHash);
			bf.putInt(EOF);

			byte[] ckpState = bf.array();
			
			ckp.write(ckpState);

			if (isToLog)
				deleteLogFile();
			deleteLastCkp();
			renameCkp(ckpPath);
			if (isToLog)
				createLogFile();
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			checkpointLock.unlock();
		}
	}

	private void renameCkp(String ckpPath) {
		String finalCkpPath = ckpPath.replace(".tmp", ".ckp");
		new File(ckpPath).renameTo(new File(finalCkpPath));
		lastCkpPath = finalCkpPath;
	}

	private void deleteLastCkp() {
		if (lastCkpPath != null)
			new File(lastCkpPath).delete();
	}

	private void deleteLogFile() {
		try {
			log.close();
			new File(logPath).delete();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public CSTState getState(CSTRequest cstRequest) {
		int eid = cstRequest.getEid();

		int lastCheckpointEid = getLastCheckpointEid();
		int lastEid = getLastEid();
		System.out.println("LAST CKP EID = " + lastCheckpointEid);
		System.out.println("EID = " + eid);
		System.out.println("LAST EID = " + lastEid);
		
		FileRecoverer fr = new FileRecoverer(id, DEFAULT_DIR);
		
		if(cstRequest instanceof CSTRequestF1) {
			CSTRequestF1 requestF1 = (CSTRequestF1)cstRequest;
			if(id == requestF1.getCheckpointReplica()) {
				// This replica is expected to send the checkpoint plus the hashes of lower and upper log portions
				checkpointLock.lock();
				byte[] ckpState = fr.getCkpState();
				checkpointLock.unlock();
	    		System.out.println("--- sending checkpoint: " + ckpState.length);
	    		CommandsInfo[] logLower = fr.getLogState(requestF1.getLogLowerSize());
	    		CommandsInfo[] logUpper = fr.getLogState(logPointers.get(requestF1.getLogUpper()), 0, requestF1.getLogUpperSize());
	    		byte[] logLowerBytes = TOMUtil.getBytes(logLower);
	    		System.out.println(logLower.length + " Log lower bytes size: " + logLowerBytes.length);
	    		byte[] logLowerHash = TOMUtil.computeHash(logLowerBytes);
	    		byte[] logUpperBytes = TOMUtil.getBytes(logUpper);
	    		System.out.println(logUpper.length + " Log upper bytes size: " + logUpperBytes.length);
	    		byte[] logUpperHash = TOMUtil.computeHash(logUpperBytes);
	    		CSTState cstState = new CSTState(ckpState, null, null, logLowerHash, null, logUpperHash, lastCheckpointEid, lastEid);
	    		return cstState;
			} else if(id == requestF1.getLogLower()) {
				// This replica is expected to send the lower part of the log
	    		System.out.print("--- sending lower log: " + requestF1.getLogLowerSize() + " from " + logPointers.get(requestF1.getCheckpointReplica())) ;
	    		CommandsInfo[] logLower = fr.getLogState(logPointers.get(requestF1.getCheckpointReplica()), 0, requestF1.getLogLowerSize());
	    		System.out.println(" " + TOMUtil.getBytes(logLower).length + " bytes");
	    		CSTState cstState = new CSTState(null, null, logLower, null, null, null, lastCheckpointEid, lastEid);
	    		return cstState;
			} else {
				// This replica is expected to send the upper part of the log plus the hash for its checkpoint
	    		System.out.println("--- sending upper log: " + requestF1.getLogUpperSize());
				checkpointLock.lock();
				fr.recoverCkpHash();
				byte[] ckpHash = fr.getCkpStateHash();
				byte[] ckpState = fr.getCkpState();
				checkpointLock.unlock();
	    		CommandsInfo[] logUpper = fr.getLogState(requestF1.getLogUpperSize());
	    		System.out.println(" " + TOMUtil.getBytes(logUpper).length + " bytes");
	    		System.out.println("--- State size: " + ckpState.length + " Current state Hash: " + ckpHash);
	    		int lastEidInState = lastCheckpointEid + requestF1.getLogUpperSize();
	    		CSTState cstState = new CSTState(null, ckpHash, null, null, logUpper, null, lastCheckpointEid, lastEidInState);
	    		return cstState;
			}
		}
//		else if(cstRequest instanceof CSTRequestFGT1) {
//			CSTRequestFGT1 requestFGT1 = (CSTRequestFGT1)cstRequest;
//			if(id == requestFGT1.getCheckpointReplica()) {
//				checkpointLock.lock();
//				byte[] ckpState = fr.getCkpState();
//				checkpointLock.unlock(); 
//	    		batches = fr.getLogState(requestFGT1.getLogSize());
//	    		System.out.println("--- sending checkpoint: " + ckpState.length);
//	            return new DefaultApplicationState(batches, lastCheckpointEid, getLastCheckpointRound(), getLastCheckpointLeader(), eid, ckpState, null);
//			} else { // Replica should send the checkpoint and log hashes
//	    		batches = fr.getLogState(requestFGT1.getLogSize() - requestFGT1.getNbrHashesBeforeCkp());
//	    		byte[] logBytes = TOMUtil.getBytes(batches);
//	    		byte[] logHash = TOMUtil.computeHash(logBytes);
//	    		fr.recoverCkpHash();
//				byte[] ckpHash = fr.getCkpStateHash();
//	            return new DefaultApplicationState(null, logHash, lastCheckpointEid, getLastCheckpointRound(), getLastCheckpointLeader(), eid, null, ckpHash);
//			}
//				
//		}
		return null;
	}
	
	public void transferApplicationState(SocketChannel sChannel, int eid) {
		FileRecoverer fr = new FileRecoverer(id, DEFAULT_DIR);
		fr.transferCkpState(sChannel);
		
//		int lastCheckpointEid = getLastCheckpointEid();
//		int lastEid = getLastEid();
//		if (eid >= lastCheckpointEid && eid <= lastEid) {
//			int size = eid - lastCheckpointEid;
//			fr.transferLog(sChannel, size);
//		}
	}

	public void setLastEid(int eid, int checkpointPeriod, int checkpointPortion) {
		super.setLastEid(eid);
		// save the file pointer to retrieve log information later
		if((eid % checkpointPeriod) % checkpointPortion == checkpointPortion -1) {
			int ckpReplicaIndex = (((eid % checkpointPeriod) + 1) / checkpointPortion) -1;
			try {
				System.out.println(" --- Replica " + ckpReplicaIndex + " took checkpoint. My current log pointer is " + log.getFilePointer());
				logPointers.put(ckpReplicaIndex, log.getFilePointer());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Updates this log, according to the information contained in the
	 * TransferableState object
	 * 
	 * @param transState
	 *            TransferableState object containing the information which is
	 *            used to updated this log
	 */
	public void update(CSTState state) {
		newCheckpoint(state.getSerializedState(), state.getStateHash());
		setLastCheckpointEid(state.getCheckpointEid());
	}

}