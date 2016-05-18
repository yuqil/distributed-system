import java.nio.ByteBuffer;

/**
 * Created by yuqil on 4/6/16.
 */
public class test {
    public static void main (String[] args) {
        byte[] tmp = int2array(358);
        System.out.println(array2int(tmp));
    }

    private static byte[] int2array (int id) {
        return ByteBuffer.allocate(4).putInt(id).array();
    }

    private static int array2int (byte[] array) {
        int val = 0;
        int weight = 1;
        for (int i = 3; i >= 0; i --) {
            int tmp = (int)array[i];
            val += tmp * weight;
            weight *= 256;
        }
        return val;
    }


}
