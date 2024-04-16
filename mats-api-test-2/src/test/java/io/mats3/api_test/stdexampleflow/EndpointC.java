package io.mats3.api_test.stdexampleflow;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;

/**
 * Mats two-stage Endpoint which calculates <code>a/b + c</code>.
 * <p />
 * Utilizes EndpointD which can calculate <code>a/b</code>.

 * @author Endre Stølsvik 2021-09-26 19:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class EndpointC {
    private static class EndpointCState {
        double c;
    }

    static void setupEndpoint(MatsFactory matsFactory) {
        MatsEndpoint<EndpointCReplyDTO, EndpointCState> ep = matsFactory
                .staged("EndpointC", EndpointCReplyDTO.class, EndpointCState.class);
        ep.stage(EndpointCRequestDTO.class, (ctx, state, msg) -> {
            // State: Keep c for next calculation
            state.c = msg.c;
            // Perform request to EndpointD to calculate 'a/b'
            ctx.request("EndpointD", new _EndpointDRequestDTO(msg.a, msg.b));
        });
        ep.lastStage(_EndpointDReplyDTO.class, (ctx, state, msg) -> {
            double result = msg.result + state.c;
            return EndpointCReplyDTO.from(result);
        });
    }

    // ===== Imported DTOs for EndpointD

    private static class _EndpointDRequestDTO {
        double a, b;

        _EndpointDRequestDTO(double a, double b) {
            this.a = a;
            this.b = b;
        }
    }

    private static class _EndpointDReplyDTO {
        double result;
    }

    // ====== EndpointC Request and Reply DTOs

    public static class EndpointCRequestDTO {
        double a, b, c;

        static EndpointCRequestDTO from(double a, double b, double c) {
            EndpointCRequestDTO ret = new EndpointCRequestDTO();
            ret.a = a;
            ret.b = b;
            ret.c = c;
            return ret;
        }
    }

    public static class EndpointCReplyDTO {
        double result;

        static EndpointCReplyDTO from(double result) {
            EndpointCReplyDTO ret = new EndpointCReplyDTO();
            ret.result = result;
            return ret;
        }
    }
}
