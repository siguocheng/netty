package io.netty.example.zpc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;

public class Nio_01 {

    public static void main(String[] args) throws IOException {

        // 打开通道
        ServerSocketChannel channel = ServerSocketChannel.open();

        // 打开selector
//        Selector selector = Selector.open();
        Selector selector = SelectorProvider.provider().openSelector();
        channel.configureBlocking(false);

        // 设置通道监听程序
//        SelectionKey selectionKey = channel.register(selector, SelectionKey.OP_ACCEPT);
        SelectionKey selectionKey = channel.register(selector, 0 );
        selectionKey.interestOps(SelectionKey.OP_ACCEPT);
        selectionKey.attach(new Object()); // 添加一个对象给这个注册到selector上的通道


        InetSocketAddress inetSocketAddress = new InetSocketAddress( 8009);
        channel.socket().bind(inetSocketAddress);

        while (true) {
            selector.select();

            Set<SelectionKey> readySelectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = readySelectionKeys.iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();

                if (key.isAcceptable()){
                    ServerSocketChannel  serverSocketChannel = (ServerSocketChannel)key.channel();
                    SocketChannel socketChannel = serverSocketChannel.accept(); // 取得服务端通道监听的客户端连接
                    socketChannel.configureBlocking(false);
                    socketChannel.register(selector, SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    System.out.println("read");
                } else if (key.isConnectable()) {
                    System.out.println("connect");
                } else if (key.isWritable()) {
                    System.out.println("write");
                }

                // 移除处理好的连接
                iterator.remove();
            }
        }
    }
}
