package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class PairedWilcoxonExternalReferenceTest {

    @Test
    void exactSmallSampleMatchesScipyOrIndependentEnumeration() throws Exception {
        List<Double> baseline = Arrays.asList(10.0, 20.0, 30.0, 40.0);
        List<Double> candidate = Arrays.asList(9.0, 18.0, 29.0, 41.0);
        String script = "import json,itertools\n"
                + "x=[-1.0,-2.0,-1.0,1.0]\n"
                + "def ranks(v):\n"
                + "  order=sorted(range(len(v)),key=lambda i:v[i])\n"
                + "  out=[0.0]*len(v);i=0\n"
                + "  while i<len(v):\n"
                + "    j=i+1\n"
                + "    while j<len(v) and v[order[j]]==v[order[i]]: j+=1\n"
                + "    r=(i+j+1)/2.0\n"
                + "    for k in range(i,j): out[order[k]]=r\n"
                + "    i=j\n"
                + "  return out\n"
                + "def exact(d):\n"
                + "  rr=ranks([abs(v) for v in d]);total=sum(rr)\n"
                + "  w=sum(r for r,v in zip(rr,d) if v>0);wm=min(w,total-w);c=0\n"
                + "  for ss in itertools.product((-1,1),repeat=len(d)):\n"
                + "    wp=sum(r for r,s in zip(rr,ss) if s>0)\n"
                + "    if min(wp,total-wp)<=wm: c+=1\n"
                + "  return c/(2**len(d))\n"
                + "try:\n"
                + "  from scipy.stats import wilcoxon\n"
                + "  p=float(wilcoxon([10,20,30,40],[9,18,29,41],method='exact').pvalue);source='scipy'\n"
                + "except ImportError:\n"
                + "  p=exact(x);source='stdlib'\n"
                + "print(json.dumps({'p':p,'source':source}))\n";
        JsonObject reference = python(script);
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);
        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(reference.get("p").getAsDouble(), result.getPValue(), 1.0e-12);
    }

    @Test
    void largeNoTieApproximationMatchesScipyOrIndependentNormalReference() throws Exception {
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 30; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(99.5 + index));
        }
        String script = "import json,math\n"
                + "n=30\n"
                + "try:\n"
                + "  from scipy.stats import wilcoxon\n"
                + "  result=wilcoxon(list(range(100,130)),[99.5+i for i in range(30)],\n"
                + "    method='approx',correction=True)\n"
                + "  p=float(result.pvalue);source='scipy'\n"
                + "except ImportError:\n"
                + "  mean=n*(n+1)/4.0;sd=math.sqrt(n*(n+1)*(2*n+1)/24.0)\n"
                + "  z=(0.0-mean-0.5)/sd;p=math.erfc(abs(z)/math.sqrt(2.0));source='stdlib'\n"
                + "print(json.dumps({'p':p,'source':source}))\n";
        JsonObject reference = python(script);
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);
        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION", result.getStatus());
        double expected = reference.get("p").getAsDouble();
        assertEquals(expected, result.getPValue(), Math.max(1.0e-12, expected * 1.0e-8));
    }

    private static JsonObject python(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("python3", "-c", script)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Python statistical reference failed with exit " + exit + ": " + output);
        }
        return JsonParser.parseString(output.toString()).getAsJsonObject();
    }
}
