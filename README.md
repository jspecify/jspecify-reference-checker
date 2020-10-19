# JSpecify Nullness Checker for the Checker Framework

This project is a
[custom checker plugin](https://checkerframework.org/manual/#creating-a-checker)
for [the Checker Framework](https://checkerframework.org/).

It is a prototype. It is not yet suitable for use on real code. To get it
working properly, we will need to do more work, including proposing new
configuration hooks in the Checker Framework itself. Running it against the
regular version of the Checker Framework will behave incorrectly, as we have
[made related changes in a temporary fork of the Checker Framework](https://github.com/jspecify/checker-framework).
In fact, even _building_ it against the regular version of the Checker Framework
does not work after
[8fcc87f5e4e28c2e4511eb8cf26092d935425fea](https://github.com/jspecify/nullness-checker-for-checker-framework/commit/8fcc87f5e4e28c2e4511eb8cf26092d935425fea).

We have made it available so that JSpecify project members can experiment with
it, collaborate on it, and discuss the easiest way to incorporate changes into
the Checker Framework itself. If all goes well, it will never be released for
general use. Instead, existing tools (including the Checker Framework) will be
modified to support JSpecify annotations directly, perhaps based on some of the
code here.

Our main work is happening in repo https://github.com/jspecify/jspecify.

This is not an officially supported product of any of the participant
organizations.
