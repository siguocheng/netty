package io.netty.example.zpc;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

public class NioServerTest {

    public static void main(String[] args) throws Exception{
        //创建一个ServerSocketChannel对象，绑定端口并配置成非阻塞模式。
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(8888), 1024);
        //下面这句必需要，否则ServerSocketChannel会使用阻塞的模式，那就不是NIO了
        serverSocketChannel.configureBlocking(false);

        //把ServerSocketChannel交给Selector监听
        Selector selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        //循环，不断的从Selector中获取准备就绪的Channel，最开始的时候Selector只监听了一个ServerSocketChannel
        //但是后续有客户端连接时，会把客户端对应的Channel也交给Selector对象
        while (true) {
            //这一步会阻塞，当有Channel准备就绪时或者超过1000秒后会返回。
            selector.select();
            System.out.println("select11");
            //获取所有的准备就绪的Channel，SelectionKey中包含中Channel信息
            Set<SelectionKey> selectionKeySet = selector.selectedKeys();
            //遍历，每个Channel都可处理
            for (SelectionKey selectionKey : selectionKeySet) {
                //如果Channel已经无效了，则跳过（如Channel已经关闭了）
                if(!selectionKey.isValid()) {
                    continue;
                }
                //判断Channel具体的就绪事件，如果是有客户端连接，则建立连接
                if (selectionKey.isAcceptable()) {
                    acceptConnection(selectionKey, selector);
                }
                //如果有客户端可以读取请求了，则读取请求然后返回数据
                if (selectionKey.isReadable()) {
                    System.out.println(readFromSelectionKey(selectionKey));
                }
            }
            //处理完成后把返回的Set清空，如果不清空下次还会再返回这些Key，导致重复处理
            selectionKeySet.clear();
        }
    }

    //客户端建立连接的方法
    private static void acceptConnection(SelectionKey selectionKey, Selector selector) throws Exception{
        System.err.println("accept connection...");
        //SelectionKey中包含选取出来的Channel的信息，我们可以从中获取，对于建立连接来说，只会有ServerSocketChannel可能触发，
        //因此这里可以把它转成ServerSocketChannel对象
        ServerSocketChannel ssc = ((ServerSocketChannel) selectionKey.channel());
        //获取客户端对应的SocketChannel，也需要配置成非阻塞模式
        SocketChannel socketChannel = ssc.accept();
        socketChannel.configureBlocking(false);
        //把客户端的Channel交给Selector监控，之后如果有数据可以读取时，会被select出来
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    //从客户端读取数据的庐江
    private static String readFromSelectionKey(SelectionKey selectionKey) throws Exception{
        //从SelectionKey中包含选取出来的Channel的信息把Channel获取出来
        SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
        //读取数据到ByteBuffer中
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        int len = socketChannel.read(byteBuffer);
        //如果读到-1，说明数据已经传输完成了，可以并闭
        if (len < 0) {
            socketChannel.close();
            selectionKey.cancel();
            return "";
        } else if(len == 0) { //什么都没读到
            return "";
        }
        byteBuffer.flip();
        doWrite(selectionKey, "Hello Nio");
        return new String(byteBuffer.array(), 0, len);
    }

    private static void doWrite(SelectionKey selectionKey, String responseMessage) throws Exception{
        System.err.println("Output message...");
        SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
        ByteBuffer byteBuffer = ByteBuffer.allocate(responseMessage.getBytes().length);
        byteBuffer.put(responseMessage.getBytes());
        byteBuffer.flip();
        socketChannel.write(byteBuffer);
    }
}
