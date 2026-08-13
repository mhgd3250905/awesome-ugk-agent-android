package Pod::Usage;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT = qw(pod2usage);

# OpenSSL's generated configdata.pm imports pod2usage only for its interactive
# help and error paths. A normal build never calls it; fail explicitly if that
# unsupported path is unexpectedly reached.
sub pod2usage {
    die "Pod::Usage is unavailable in the minimal OpenSSL Windows build host\n";
}

1;
