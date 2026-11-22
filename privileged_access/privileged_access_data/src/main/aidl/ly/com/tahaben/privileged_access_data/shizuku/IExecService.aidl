package ly.com.tahaben.privileged_access_data.shizuku;

import android.os.Bundle;

// Runs inside the process Shizuku starts (shell uid). The two transaction numbers are Shizuku's: destroy() must be 16777114.
interface IExecService {
    void destroy() = 16777114;

    // argv is one command from PrivilegedCommand. Returns "exit" (int) and "output" (String).
    Bundle run(in String[] argv) = 1;
}
