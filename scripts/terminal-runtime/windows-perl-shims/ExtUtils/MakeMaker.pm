package ExtUtils::MakeMaker;

use strict;
use warnings;

# Git for Windows omits ExtUtils::MakeMaker. OpenSSL reaches it only via
# IPC::Cmd::can_run(), which needs MM->maybe_command() to locate build tools.
# This narrow shim is a build-host workaround, never part of the Android SDK.
package MM;

sub maybe_command {
    my ($class, $path) = @_;
    return unless defined $path && -f $path;
    return $path if -x $path;
    return $path if $path =~ /\.(?:exe|bat|cmd|com)$/i;
    return;
}

1;
