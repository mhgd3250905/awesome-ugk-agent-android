package Locale::Maketext::Simple;

use strict;
use warnings;

# Git for Windows ships Params::Check / IPC::Cmd, which OpenSSL's Configure
# script uses, but omits this localisation-only dependency. OpenSSL only needs
# unlocalised diagnostic strings during the build, so preserve the template and
# substitute the %1 placeholder form used by those modules.
sub import {
    my ($class, @arguments) = @_;
    my $caller = caller;
    no strict 'refs';
    *{"${caller}::loc"} = \&loc;
}

sub loc {
    my ($template, @arguments) = @_;
    $template =~ s/%(\d+)/
        defined $arguments[$1 - 1] ? $arguments[$1 - 1] : "%$1"
    /gex;
    return $template;
}

1;
