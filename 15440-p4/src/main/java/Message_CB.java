import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is a class for asynchronous message handling (call back messages).
 * Created by yuqil on 4/6/16.
 */
public class Message_CB implements ProjectLib.MessageHandling {
    // for each commit file, has a seperate message queue
    public static ConcurrentHashMap<String, Queue<ProjectLib.Message>> map = new ConcurrentHashMap<String, Queue<ProjectLib.Message>>();

    /**
     * Deliver Message function for call back message delivery.
     * @param message
     * @return true if exist such commit process, false if the commit process has ended.
     */
    public boolean deliverMessage(ProjectLib.Message message) {
        byte[] content = message.body;
        MessageTemplate template = MakeMessage.deserialize(content, MessageTemplate.class);
        String id = template.filename;
        if (map.containsKey(id)) {
            map.get(id).offer(message);
            return true;
        }
        return false;
    }

    /**
     * Add new commit message queue.
     * @param id filename
     * @return a new message queue for the file
     */
    public Queue<ProjectLib.Message> addNewCommit(String id) {
        map.put(id, new LinkedList<ProjectLib.Message>());
        return map.get(id);
    }

    /**
     * Stop voting for a file, remove the message queue to stop receiving message
     * @param id filename
     * @return message queue
     */
    public Queue<ProjectLib.Message> stopVoting(String id) {
        Queue<ProjectLib.Message> queue = map.get(id);
        map.remove(id);
        return queue;
    }
}
