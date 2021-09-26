package io.mats3.api_test.stdexampleflow;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;

/**
 * Vulgarly complex Mats Endpoint to calculate <code>a*b - (c/d + e)</code>.
 * <p/>
 * Utilizes EndpointB which can calculate <code>a*b</code>, and EndpointC which can calculate <code>a/b + c</code>.
 *
 * @author Endre Stølsvik 2021-09-26 19:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class EndpointA {

    private static class EndpointAState {
        double result_a_multiply_b;
        double c, d, e;
    }

    // Vulgarly complex Mats Endpoint to calculate 'a*b - (c/d + e)'.
    static void setupEndpoint(MatsFactory matsFactory) {
        MatsEndpoint<EndpointAReplyDTO, EndpointAState> ep = matsFactory
                .staged("EndpointA", EndpointAReplyDTO.class, EndpointAState.class);

        ep.stage(EndpointARequestDTO.class, (ctx, state, msg) -> {
            // State: Keep c, d, e for next calculation
            state.c = msg.c;
            state.d = msg.d;
            state.e = msg.e;
            // Perform request to EndpointB to calculate 'a*b'
            ctx.request("EndpointB", _EndpointBRequestDTO.from(msg.a, msg.b));
        });
        ep.stage(_EndpointBReplyDTO.class, (ctx, state, msg) -> {
            // State: Keep the result from 'a*b' calculation
            state.result_a_multiply_b = msg.result;
            // Perform request to Endpoint C to calculate 'c/d + e'
            ctx.request("EndpointC", _EndpointCRequestDTO.from(state.c, state.d, state.e));
        });
        ep.lastStage(_EndpointCReplyDTO.class, (ctx, state, msg) -> {
            // We now have the two sub calculations to perform final subtraction
            double result = state.result_a_multiply_b - msg.result;
            // Reply with our result
            return EndpointAReplyDTO.from(result);
        });
    }

    // ===== Imported DTOs for EndpointB

    private static class _EndpointBRequestDTO {
        double a, b;

        static _EndpointBRequestDTO from(double a, double b) {
            _EndpointBRequestDTO ret = new _EndpointBRequestDTO();
            ret.a = a;
            ret.b = b;
            return ret;
        }
    }

    private static class _EndpointBReplyDTO {
        double result;
    }

    // ===== Imported DTOs for EndpointC

    private static class _EndpointCRequestDTO {
        double a, b, c;

        static _EndpointCRequestDTO from(double a, double b, double c) {
            _EndpointCRequestDTO ret = new _EndpointCRequestDTO();
            ret.a = a;
            ret.b = b;
            ret.c = c;
            return ret;
        }
    }

    private static class _EndpointCReplyDTO {
        double result;
    }

    // ====== EndpointA Request and Reply DTOs

    public static class EndpointARequestDTO {
        double a, b, c, d, e;

        public static EndpointARequestDTO from(double a, double b, double c, double d, double e) {
            EndpointARequestDTO ret = new EndpointARequestDTO();
            ret.a = a;
            ret.b = b;
            ret.c = c;
            ret.d = d;
            ret.e = e;
            return ret;
        }
    }

    public static class EndpointAReplyDTO {
        double result;

        public static EndpointAReplyDTO from(double result) {
            EndpointAReplyDTO ret = new EndpointAReplyDTO();
            ret.result = result;
            return ret;
        }
    }
}
