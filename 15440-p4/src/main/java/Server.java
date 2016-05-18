/* Skeleton code for Server  */

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements ProjectLib.CommitServing {
	/* Messaging APIs and file operations */
	public static ProjectLib PL;
	public static Message_CB message_cb;
	public static FileOperation file_operation;

	/* In-memory Logs */
	public static ConcurrentHashMap<String, FileOperation.Log> log_map = new ConcurrentHashMap<String, FileOperation.Log>();
	public static ConcurrentHashMap<String, Integer> result_map = new ConcurrentHashMap<String, Integer>();
	public static HashSet<String> finished = new HashSet<String>();

	/* Used for recovery logs */
	public static ConcurrentHashMap<String, FileOperation.Log> log_map2 = new ConcurrentHashMap<String, FileOperation.Log>();
	public static ConcurrentHashMap<String, Integer> result_map2 = new ConcurrentHashMap<String, Integer>();
	public static boolean recovery_finished = false;

	/* Global Macro */
	public static final byte ASK_USER_MSG = 0;
	public static final byte REPLY_USER_COMMIT = 1;
	public static final byte REPLY_USER_FAILURE = 2;

	public static final byte USER_AGREE = 3;
	public static final byte USER_DISAGREE = 4;
	public static final byte USER_ACK = 5;

	public static final int COMMIT_SUCCESS = 6;
	public static final int COMMIT_FAILURE = 7;
	public static final int COMMIT_INCOMPLETE = 8;
	public static final int TIMEOUT = 3000;

	/* commit process type */
	public static final int NORMAL_COMMIT = -100;
	public static final int RECOVERY_ABORT = -110;
	public static final int RECOVERY_DISTRIBUTE_DECISION = -120;

	public static void main ( String args[] ) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");

		// recovery from log
		file_operation = new FileOperation("");
		recovery();

		// set up server for messages handling
		Server srv = new Server();
		message_cb = new Message_CB();
		PL = new ProjectLib( Integer.parseInt(args[0]), srv, message_cb);

		// recovery those commits with no decisions or with no ack received.
		for (String key : log_map2.keySet()) {
			if (!result_map2.containsKey(key)) {
				System.err.println("Server recovery : " + key + " ABORT ");
				TwoPhaseCommit commitThread = new TwoPhaseCommit(log_map2.get(key), RECOVERY_ABORT);
				commitThread.start();
			} else {
				System.err.println("Server recovery : " + key + " DISTRIBUTE DECISIONS " + result_map.get(key));
				int decision = RECOVERY_DISTRIBUTE_DECISION + result_map2.get(key);
				TwoPhaseCommit commitThread = new TwoPhaseCommit(log_map2.get(key), decision);
				commitThread.start();
			}
		}
		recovery_finished = true;

		// main loop: discard these information are not used.
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			System.err.println( "Server: Got unknown message from " + msg.addr);
		}
	}


	/**
	 * Start commit. Launch a new process.
	 * @param filename collage name
	 * @param img image content
	 * @param sources source files
     */
	public void startCommit( String filename, byte[] img, String[] sources ) {
		TwoPhaseCommit commitThread = new TwoPhaseCommit(filename, img, sources, NORMAL_COMMIT);
		commitThread.start();
	}

	/**
	 * Read from log and construct recovery logs.
	 * @throws IOException
     */
	public static void recovery() throws IOException {
		if (!file_operation.hasFile("LOG.txt")) return;
		BufferedReader br = file_operation.getLogReader();
		String line;
		while ((line = br.readLine()) != null) {
			if (line.length() == 0) continue;
			String[] tokens = line.split(" ");
			if (tokens.length < 2) continue;
			String type = tokens[1];
			if (type.equals("log")) {
				String[] source = new String[tokens.length - 2];
				System.arraycopy(tokens, 2, source, 0, tokens.length - 2);
				log_map2.put(tokens[0], new FileOperation.Log(tokens[0], null, source));
			} else if (type.equals("result")) {
				result_map2.put(tokens[0], Integer.parseInt(tokens[2]));
			} else if (type.equals("finished")) {
				log_map2.remove(tokens[0]);
				result_map2.remove(tokens[0]);
			}
		}
		br.close();
	}


	/**
	 * Process for two phase commit.
	 */
	private static class TwoPhaseCommit implements Runnable {
		private Queue<ProjectLib.Message> queue;  // message queue
		private String filename;
		private byte[] img;
		private String[] sources;
		private int commit = COMMIT_INCOMPLETE;   // commit decision
		private int type;   // NORMAL_COMMIT or RECOVERY_ABORT or RECOVERY_DISTRIBUTE_DECISION
		private Thread t;

		public TwoPhaseCommit(String filename, byte[] img, String[] sources, int type) {
			this.filename = filename;
			this.img = img;
			this.sources = sources;
			this.type = type;
		}

		public TwoPhaseCommit(FileOperation.Log log, int type) {
			this.filename = log.filename;
			this.img = log.img;
			this.sources = log.sources;
			this.type = type;
		}


		public void run() {
			// get all sources and users
			HashMap<String, ArrayList<String>> user_files = getAllInfo();

			if (type == NORMAL_COMMIT) {
				// get vote decisions after recovery finishes
				while (!recovery_finished) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace(System.err);
					}
				}
				sendVoteRequests(user_files);
				commit = getVoteDecisions(user_files);
			} else if (type == RECOVERY_ABORT) {
				// abort operation with no decision
				commit = COMMIT_FAILURE;
				result_map.put(filename, commit);
				file_operation.writeResult(filename, commit);
			} else {
				commit = (type - RECOVERY_DISTRIBUTE_DECISION);
			}

			// distribute decisions
			PL.fsync();
			distributeDecisions(user_files, commit);
			file_operation.writeFinishedLog(filename);
			finished.add(filename);
			System.err.println("Server: " + filename + " finished ");
		}

		/**
		 * Get user and their source files from message
		 * @return user-sources map.
         */
		private HashMap<String, ArrayList<String>> getAllInfo() {
			HashMap<String, ArrayList<String>> user_files = new HashMap<String, ArrayList<String>>();
			for (String source : sources) {
				// construct message body: id + msg_type + img_length + img + sourcefile
				String[] tokens = source.split(":");
				if (!user_files.containsKey(tokens[0])) {
					user_files.put(tokens[0], new ArrayList<String>());
				}
				user_files.get(tokens[0]).add(tokens[1]);
			}
			return user_files;
		}

		/**
		 * Send vote requests to users.
		 * @param user_files user-sources map.
         */
		private void sendVoteRequests(HashMap<String, ArrayList<String>> user_files) {
			// send request to vote for collage
			for (String user : user_files.keySet()) {
				ArrayList<String> files = user_files.get(user);
				MessageTemplate template = new MessageTemplate(filename, files, ASK_USER_MSG, img);
				byte[] msg_body = MakeMessage.serialize(template);

				ProjectLib.Message msg = new ProjectLib.Message(user, msg_body);
				PL.sendMessage(msg);
			}
		}

		/**
		 * Collect vote decisions
		 * @param user_files user-sources map.
         * @return commit decision
         */
		private int getVoteDecisions(HashMap<String, ArrayList<String>> user_files) {
			long start = System.currentTimeMillis();  // start time
			long end = start;                         // end time
			int crt = 0;
			int total = user_files.size();
			commit = COMMIT_INCOMPLETE;

			while (end - start <= TIMEOUT && crt < total) {
				if (!Message_CB.map.get(filename).isEmpty()) {
					MessageTemplate template = MakeMessage.deserialize(queue.poll().body, MessageTemplate.class);
					if (template.opcode == USER_DISAGREE) {
						commit = COMMIT_FAILURE;  // if one disagree, fails.
						break;
					} else {
						crt++;
					}
				}
				if (commit == COMMIT_FAILURE) break;
				else end = System.currentTimeMillis();
			}

			// if get all votes succeed
			if (crt >= total) commit = COMMIT_SUCCESS;
			result_map.put(filename, commit);
			if (commit == COMMIT_SUCCESS)
				file_operation.addFile(filename, img);
			file_operation.writeResult(filename, commit);
			PL.fsync();
			System.err.println("Server: " + filename + " result: " + crt + "/" + total);
			return commit;
		}

		/**
		 * Distribute decisions and waits until all replies
		 * @param user_files user-sources map.
		 * @param commit commit decision
         */
		private void distributeDecisions(HashMap<String, ArrayList<String>> user_files, int commit) {
			int reply = commit == COMMIT_SUCCESS ? REPLY_USER_COMMIT : REPLY_USER_FAILURE;
			this.queue = message_cb.addNewCommit(filename);
			for (String user : user_files.keySet()) {
				ArrayList<String> files = user_files.get(user);
				byte[] msg_body = MakeMessage.serialize(new MessageTemplate(filename, files, reply, null));
				PL.sendMessage(new ProjectLib.Message(user, msg_body));
			}

			// wait until all messages received
			while (user_files.size() != 0) {
				long start = System.currentTimeMillis();
				long end = start;
				while (end - start <= TIMEOUT && user_files.size() != 0) {
					if (!queue.isEmpty()) {
						ProjectLib.Message msg = queue.poll();
						MessageTemplate template = MakeMessage.deserialize(msg.body, MessageTemplate.class);
						if (template.opcode == USER_ACK) {
							user_files.remove(msg.addr);
						}
					}
					end = System.currentTimeMillis();
				}

				for (String user : user_files.keySet()) {
					ArrayList<String> files = user_files.get(user);
					byte[] msg_body = MakeMessage.serialize(new MessageTemplate(filename, files, reply, null));
					PL.sendMessage(new ProjectLib.Message(user, msg_body));
				}
			}
			message_cb.stopVoting(filename);
		}


		public void start() {
			this.queue = message_cb.addNewCommit(filename);
			if (type == NORMAL_COMMIT) {
				log_map.put(filename, new FileOperation.Log(filename, img, sources));
				file_operation.writeLog(log_map.get(filename));
				PL.fsync();
				System.err.print("Server: Got " + type + " request to commit " + log_map.get(filename));
			} else {
				System.err.print("Server: Got " + type + " request to commit " + log_map2.get(filename));
			}

			if (t == null) {
				t = new Thread(this);
				t.start();
			}
		}
	}
}

