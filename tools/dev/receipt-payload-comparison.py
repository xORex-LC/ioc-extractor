#!/usr/bin/env python3
"""One bounded receipt payload experiment over the common frozen service comparison driver."""
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('journal_comparison', Path(__file__).with_name('typed-journal-comparison.py'))
JOURNAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JOURNAL)


class ReceiptWindow(JOURNAL.JournalWindow):
    def finish(self):
        result = super().finish()
        result['receipt_families_after'] = self.request('receipt-families')
        scans = [self.request('receipt-read-burst?count=1') for _ in range(20)]
        fingerprints = {(scan['payloadSha256'], scan['rowsPerScan'], scan['receiptCount']) for scan in scans}
        if len(fingerprints) != 1:
            raise RuntimeError('Receipt replay changed between scans')
        reads = dict(scans[0], samplesNanos=[scan['samplesNanos'][0] for scan in scans],
                     allocatedThreadBytes=[scan['allocatedThreadBytes'][0] for scan in scans])
        result['receipt_reads_after'] = dict(reads, estimator='nearest rank; 20 scans, p99 is sample maximum',
                p95_ns=JOURNAL.ACK.percentile(reads['samplesNanos'], .95),
                p99_ns=JOURNAL.ACK.percentile(reads['samplesNanos'], .99))
        return result


if __name__ == '__main__':
    JOURNAL.main(experiment='receipts', window=ReceiptWindow)
