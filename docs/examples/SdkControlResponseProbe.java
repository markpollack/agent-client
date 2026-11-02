import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.types.control.ControlResponse;

/** No process/model invocation. The desired SDK contract is duplicate-free control JSON. */
public final class SdkControlResponseProbe {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var strict = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        int invalid = 0;
        for (var response : List.of(ControlResponse.success("probe", Map.of("permission_decision", "allow")),
                ControlResponse.error("probe", "test denial"))) {
            String bytes = mapper.writeValueAsString(response);
            System.out.println(bytes);
            try { strict.readTree(bytes); }
            catch (JsonProcessingException failure) {
                invalid++;
                System.out.println("DUPLICATE_FREE=false " + response.response().subtype() + " " + failure.getOriginalMessage());
            }
        }
        if (invalid != 0) throw new AssertionError("SDK emitted " + invalid + " control responses rejected by strict JSON readers");
        System.out.println("DUPLICATE_FREE=true");
    }
}
