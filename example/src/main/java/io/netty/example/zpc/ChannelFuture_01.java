package io.netty.example.zpc;

import io.netty.util.concurrent.*;

import java.util.concurrent.ThreadFactory;

public class ChannelFuture_01 {

    public static void main(String[] args) throws InterruptedException {

        EventExecutor executor = new SelfEventExecutor(); // 用于异步执行回调函数的线程执行器
        final DefaultPromise<String> promise = new DefaultPromise<String>(executor);

        // 像这样添加多个。也可以调用addListeners添加多个
        promise.addListener(new GenericFutureListener<Future<? super String>>() {
            @Override
            public void operationComplete(Future<? super String> future) throws Exception {
                System.out.println("执行完成结果：" + future.get());
            }
        });

        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
//                    Thread.sleep(3000);
                    int a = 1/0;
                    promise.setSuccess("hello world!"); // 线程任务执行成功，设置成功标识，触发添加的listen
                } catch (Exception ex) {
                    promise.setFailure(ex);
                    ex.printStackTrace();
                }
            }
        });
        thread.start();

        promise.sync(); // 线程阻塞
        System.out.println("==================");
    }

    static class SelfEventExecutor extends SingleThreadEventExecutor {


        public SelfEventExecutor() {
            this(null);
        }

        public SelfEventExecutor(EventExecutorGroup parent) {
            this(parent, new DefaultThreadFactory(SelfEventExecutor.class));
        }

        public SelfEventExecutor(EventExecutorGroup parent, ThreadFactory threadFactory) {
            super(parent, threadFactory, true);
        }

        @Override
        protected void run() {
            Runnable task = takeTask();
            if (task != null) {
                task.run();
            }
        }
    }
}
