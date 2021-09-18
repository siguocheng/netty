package io.netty.example.zpc;

import java.util.concurrent.*;

public class CallTest implements Callable<String> {

    @Override
    public String call() throws Exception {
        TimeUnit.SECONDS.sleep(5);
        return "aaaa";
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {


    }
}
