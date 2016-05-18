/* Skeleton code for UserNode */

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserNode implements ProjectLib.MessageHandling {
	public static String myId;
	public static ProjectLib PL;
	public static FileOperation fileOperation;
	public static HashSet<String> locked_file = new HashSet<String>();
	public static HashMap<String, Integer> result_map = new HashMap<String, Integer>();

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

	public UserNode(String id) {
		myId = id;
	}

	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		fileOperation = new FileOperation("");
		recovery();
		UserNode UN = new UserNode(args[1]);
		PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN);
	}

	/**
	 * Deliver message sequentially.
	 * @param msg
	 * @return
     */
	public boolean deliverMessage( ProjectLib.Message msg ) {
		ProjectLib.Message reply;
		MessageTemplate template = MakeMessage.deserialize(msg.body, MessageTemplate.class);
		switch (template.opcode) {
			case ASK_USER_MSG:
				reply = reply_Collage(msg, template);
				break;
			case REPLY_USER_COMMIT:
				reply = reply_ACK(msg, USER_AGREE, template);
				break;
			case REPLY_USER_FAILURE:
				reply = reply_ACK(msg, USER_DISAGREE, template);
				break;
			default:
				reply = new ProjectLib.Message(msg.addr, new byte[5]);
				System.out.println( myId + ": Got message from " + msg.addr + " " +  new String(msg.body));
				return false;
		}
		PL.sendMessage(reply);
		return true;
	}

	/**
	 * Reply ack to decision.
	 * @param msg message
	 * @param reply_code reply code ACK
	 * @param template message template
     * @return a new reply message to send
     */
	private ProjectLib.Message reply_ACK (ProjectLib.Message msg, int reply_code, MessageTemplate template) {
		byte[] msg_body = MakeMessage.serialize(new MessageTemplate(template.filename, (String[]) null, USER_ACK, null));
		fileOperation.writeResult(template.sources, template.opcode);

		// delete all files if needed and remove locks
		for (int i = 0; i < template.sources.length; i ++) {
			if (reply_code == USER_AGREE) {
				fileOperation.removeFile(template.sources[i]);
			}
			locked_file.remove(template.sources[i]);
			fileOperation.writeFinishedLog(template.sources[i]);
		}
		return new ProjectLib.Message(msg.addr, msg_body);
	}


	/**
	 * Ask user about commit
	 * @param msg message
	 * @param template template message
	 * @return a new reply message to send back
     */
	private ProjectLib.Message reply_Collage(ProjectLib.Message msg, MessageTemplate template) {
		// check if all files are valid for use
		boolean valid = true;
		for (String file : template.sources) {
			if (!fileOperation.hasFile(file) || locked_file.contains(file)) {
				valid = false;
				break;
			}
		}

		// if not valid, return failure
		// else ask user whether to agree
		boolean agree = false;
		if (valid) {
			locked_file.addAll(Arrays.asList(template.sources));
			agree = PL.askUser(template.img, template.sources);
			fileOperation.writeLog(template.sources, template.filename);
		}

		// send back vote result
		MessageTemplate template1;
		if (agree) {
			template1 = new MessageTemplate(template.filename, (String[]) null, USER_AGREE, null);
		} else {
			template1 = new MessageTemplate(template.filename, (String[]) null, USER_DISAGREE, null);
		}

		System.err.println(myId+ ": " + template.filename + " result " + template1.opcode);
		byte[] msg_body = MakeMessage.serialize(template1);
		return new ProjectLib.Message(msg.addr, msg_body);
	}


	/**
	 * Recovery from logs
	 * @throws IOException
     */
	private static void recovery() throws IOException {
		if (!fileOperation.hasFile("LOG.txt")) return;
		BufferedReader br = fileOperation.getLogReader();
		String line;
		while ((line = br.readLine()) != null) {
			if (line.length() == 0) continue;
			System.out.println("NODE" + line);
			String[] tokens = line.split(" ");
			if (tokens.length < 2) continue;
			if (tokens[1].equals("log")) {
				locked_file.add(tokens[0]);
			} else if (tokens[1].equals("result")) {
				int result = Integer.parseInt(tokens[2]);
				result_map.put(tokens[0], result);
			} else if (tokens[1].equals("finished")) {
				locked_file.remove(tokens[0]);
				result_map.remove(tokens[0]);
			}
		}
		br.close();

		// for all files with a result but no finish log,
		// redo all operations
		for (String key : result_map.keySet()) {
			int reply_code = result_map.get(key);
			if (reply_code == USER_AGREE) {
				fileOperation.removeFile(key);
			}
			locked_file.remove(key);
			fileOperation.writeFinishedLog(key);
			System.err.println(myId+ " recovery : " + key + " result " + reply_code);
		}
	}
}

