package io.mats3.spring.test.apptest1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.springframework.stereotype.Service;

import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 *
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Service
public class AppServiceMatsInvoker {
    @Inject
    private MatsFuturizer _matsFuturizer;

    public SpringTestDataTO invokeMatsWithFuturizer(double number, String string) {
        CompletableFuture<Reply<SpringTestDataTO>> completableFuture = _matsFuturizer.futurizeNonessential("TraceId",
                "AppServiceMatsInvoker",
                AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT,
                SpringTestDataTO.class,
                new SpringTestDataTO(number, string));

        try {
            return completableFuture.get(10, TimeUnit.SECONDS).get();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Couldn't get result", e);
        }
    }
}
