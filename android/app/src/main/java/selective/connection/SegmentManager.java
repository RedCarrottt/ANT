package selective.connection;

/* Copyright (c) 2018, contributors. All rights reserved.
 *
 * Contributor: 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.ListIterator;

import kr.ac.skku.nyx.selectiveconnection.Logger;

/*
    Segment is the minimum unit of the sending data through the network.
    Segment header (sequence # + flag_len) is 8bytes (4bytes + 4bytes)
 */
class Segment {
    int seq_no;
    int flag_len;
    byte[] data;

    Segment() {
        seq_no = -1;
        flag_len = 0;
        data = new byte[SegmentManager.kSegSize + SegmentManager.kSegHeaderSize];
    }
}
class SegmentManager {
    static private SegmentManager instance = null;

    static public final int kSegMxQueueSize = 104857600;
    static public final int kSegSize = 512;
    static public final int kSegThreshold = 1024;
    static public final int kSegQueueThreshold = kSegThreshold / 512;
    static public final int kSegFreeThreshold = 256;

    static public final int kSegHeaderSize = 8;

    static public final int kSegLenOffset = 0;
    static public final int kSegFlagOffset = 15;
    static public final int kSegLenMask = 0x00007fff;
    static public final int kSegFlagMask = 0x00008000;

    static public final int kSegSend = 0;
    static public final int kSegRecv = 1;
    static public final int kSegMaxQueueType = 2;

    static public final short kSegFlagMF = 1;

    private int seq_no;
    public int queue_threshold;

    private int[] next_seq_no;

    private LinkedList<Segment>[] queue;
    private LinkedList<Segment> failed_queue;
    private LinkedList<Segment>[] pending_queue;
    private int[] queue_size;
    private String tag = "SegmentManager";

    private LinkedList<Segment> free_list;
    private int free_list_size;

    public int wfd_state = 0;

    // Macro
    static public int mGetSegLenBits(int x) {
        return (x & kSegLenMask) >> kSegLenOffset;
    }

    static public int mGetSegFlagBits(int x) {
        return (x & kSegFlagMask) >> kSegFlagOffset;
    }

    static public int mSetSegLenBits(int x, int dest) {
        return (dest |= (x << kSegLenOffset) & kSegLenMask);
    }

    static public int mSetSegFlagBits(int x, int dest) {
        return (dest |= (x << kSegFlagOffset) & kSegFlagMask);
    }

    private SegmentManager() {
        queue = new LinkedList[kSegMaxQueueType];
        for (int i=0; i<kSegMaxQueueType; i++) {
            queue[i] = new LinkedList<Segment>();
        }

        failed_queue = new LinkedList<Segment>();
        pending_queue = new LinkedList[kSegMaxQueueType];
        for (int i=0; i<kSegMaxQueueType; i++) {
            pending_queue[i] = new LinkedList<Segment>();
        }

        queue_size = new int[kSegMaxQueueType];
        for (int i=0; i<kSegMaxQueueType; i++) {
            queue_size[i] = 0;
        }

        next_seq_no = new int[kSegMaxQueueType];
        for (int i=0; i<kSegMaxQueueType; i++) {
            next_seq_no[i] = 0;
        }

        free_list = new LinkedList<Segment>();
        seq_no = 0;
        queue_threshold = 0;
    }


    static public SegmentManager get_instance() {
        if (instance == null)
            instance = new SegmentManager();

        return instance;
    }

    int get_seq_no(int len) {
        int ret = seq_no;
        seq_no += len;

        return ret;
    }

    public int send_to_segment_manager(byte[] data, int len) {
        if (data == null || len <= 0) throw new AssertionError();

        int offset = 0;
        int num_of_segments = (int)((len + kSegSize - 1) / kSegSize);
        int allocated_seq_no = get_seq_no(num_of_segments);
        int seg_idx;
        for (seg_idx = 0; seg_idx < num_of_segments; seg_idx++) {
            int seg_len = (len - offset < kSegSize)? (len - offset) : kSegSize;
            Segment seg = get_free_segment();

            seg.flag_len = mSetSegLenBits(seg_len, seg.flag_len);
            seg.seq_no = allocated_seq_no++;

            System.arraycopy(data, offset, seg.data, kSegHeaderSize, seg_len);
            offset += seg_len;

            if (offset < len) seg.flag_len = mSetSegFlagBits(kSegFlagMF, seg.flag_len);

            serialize_segment_header(seg);

            enqueue(kSegSend, seg);
        }

        return 0;
    }

    private void serialize_segment_header(Segment seg) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(seg.seq_no);
        byte[] net_seq_no = buffer.array();

        buffer = ByteBuffer.allocate(4);
        buffer.putInt(seg.flag_len);
        byte[] net_flag_len = buffer.array();

        System.arraycopy(net_seq_no, 0, seg.data, 0, 4);
        System.arraycopy(net_flag_len, 0, seg.data, 4, 4);
    }

    public byte[] recv_from_segment_manager(ProtocolData pd) {
        if (pd == null) throw new AssertionError();

        byte[] serialized = null;
        int offset = 0;
        int data_size = 0;
        boolean cont = false;

        Segment seg = dequeue(kSegRecv);
        ProtocolManager.parse_header(Arrays.copyOfRange(seg.data, kSegHeaderSize, seg.data.length), pd);
        if (pd.len == 0) return null;

        //Logger.print(tag, "pd.len is " + pd.len);
        serialized = new byte[pd.len];

        // Handle the first segment of the data bulk, because it contains protocol data
        data_size = mGetSegLenBits(seg.flag_len) - ProtocolManager.kProtHeaderSize;
        System.arraycopy(seg.data, kSegHeaderSize + ProtocolManager.kProtHeaderSize, serialized, offset, data_size);
        offset += data_size;

        cont = (mGetSegFlagBits(seg.flag_len) == kSegFlagMF);
        free_segment(seg);

        while (cont) {
            seg = dequeue(kSegRecv);
            //Logger.print(tag, "Dequeing recved data : " + Integer.toString(seg.seq_no));
            data_size = mGetSegLenBits(seg.flag_len);
            System.arraycopy(seg.data, kSegHeaderSize, serialized, offset, data_size);
            cont = (mGetSegFlagBits(seg.flag_len) == kSegFlagMF);
            offset += data_size;
            free_segment(seg);
        }

        if (serialized == null) throw new AssertionError();

        return serialized;
    }

    public void enqueue(int type, Segment seg) {
        if (type >= kSegMaxQueueType) throw new AssertionError();

        synchronized (queue[type]) {
            boolean segment_enqueued = false;

            if (seg.seq_no == next_seq_no[type]) {
                next_seq_no[type]++;
                queue[type].offerLast(seg);
                queue_size[type]++;
                segment_enqueued = true;
            } else {
                if (seg.seq_no < next_seq_no[type]) {
                    Logger.print(tag, ((type==kSegSend)? "Sending Queue" : "Recving Queue") + Integer.toString(seg.seq_no) + ":"+ Integer.toString(next_seq_no[type]));
                    throw new AssertionError();
                }

                ListIterator<Segment> it = pending_queue[type].listIterator();
                while (it.hasNext()) {
                    Segment walker = it.next();

                    if (walker.seq_no > seg.seq_no) break;
                }

                it.add(seg);
            }

            ListIterator<Segment> it = pending_queue[type].listIterator();
            while (it.hasNext()) {
                Segment walker = it.next();

                if (walker.seq_no != next_seq_no[type]) break;

                queue[type].offerLast(walker);
                queue_size[type]++;
                next_seq_no[type]++;
                segment_enqueued = true;

                it.remove();
            }

            if (segment_enqueued) {
                //Logger.print(tag, "WakeUP!");
                queue[type].notifyAll();
            }
        }

        if (type == kSegSend) {
            /*
            if (queue_size[type] > queue_threshold) {
                Core.get_instance().increase_adapter();
            } else if (queue_size[type] == 0) {
                Core.get_instance().decrease_adapter();
            }
            */
        }
    }

    public Segment dequeue(int type) {
        synchronized (queue[type]) {
            if (queue_size[type] == 0) {
                try {
                    //Logger.print(tag, "Wating for queue is filled");
                    queue[type].wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            Segment ret = queue[type].pollFirst();
            queue_size[type]--;

            return ret;
        }
    }

    public Segment get_free_segment() {
        Segment ret = null;
        synchronized (free_list) {
            if (free_list_size == 0) {
                ret = new Segment();
            } else {
                ret = free_list.pop();
                free_list_size--;
            }

            if (ret == null) throw new AssertionError();

            ret.seq_no = -1;
            ret.flag_len = 0;
        }
        return ret;
    }

    public void free_segment(Segment seg) {
        synchronized (free_list) {
            free_list.push(seg);
            free_list_size++;

            if (free_list_size > kSegFreeThreshold) {
                release_segment_from_free_list(kSegFreeThreshold / 2);
            }
        }
    }

    public void free_segment_all() {
        synchronized (free_list) {
            release_segment_from_free_list(0);
        }
    }

    private void release_segment_from_free_list(int threshold) {
        while (free_list_size > threshold) {
            free_list.pop();
            free_list_size--;
        }
    }

    public void failed_sending(Segment seg) {
        synchronized (failed_queue) {
            failed_queue.offerLast(seg);
        }
    }

    public Segment get_failed_sending() {
        Segment ret = null;
        synchronized (failed_queue) {
            ret = failed_queue.pollFirst();
        }

        return ret;
    }
}