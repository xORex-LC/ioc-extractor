package com.iocextractor.adapter.out.transport.smb;

import java.time.Duration;
import java.util.Optional;

interface SmbChangeNotifyPending {

    Optional<SmbChangeNotifyResult> await(Duration timeout);

    boolean cancel();
}
