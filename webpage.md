MiTextExplorer
==============

This is a tool that allows interactive exploration of text and document covariates.
See [the paper](http://brenocon.com/oconnor.mitextexplorer.illvi2014.pdf) for information.
Currently, an experimental system is available. Contact brenocon@gmail.com ([http://brenocon.com](http://brenocon.com)) with questions.

How to run
==========

Get the application: <b><a href=http://brenocon.com/te/te.jar>te.jar</a></b>

Get one of the example datasets: <a href=http://brenocon.com/te/bible.zip>bible.zip</a> or <a href=http://brenocon.com/te/sotu.zip>sotu.zip</a>.

Requires Java version 8. Check with `java -version`; it must be at least `"1.8.0"`.

Launch it with one argument, the config file of the corpus you want to view.  For example:

    java -jar te.jar sotu/config.conf

(If it is incredibly slow, try `java -Xmx2g`.)

Data format
===========

Each line is one document, encoded as a JSON object.
There are two mandatory keys:

 * `id` (or `docid`): a string that is a unique identifier for this document.
 * `text`: a string of the document's text.

Other keys in the JSON object are covariates.
They have to be listed in `schema.conf` to be used.

TODO: covariates in separate file, and in CSV...

Configuration options
=====================

The examples are set up with two config files,

  * `config.conf`: controls the application
  * `schema.conf`: describes the covariates (metadata variables). Currently, you have to specify all of them. (TODO, automatic detection)

The application is launched by giving it the full path to the main config file.
For an example to adapt to your own data, start with bible/config.conf.

In `config.conf`, parameters include:

  * `data`: the filename of the data. Either absolute, or relative to the config file's directory.
  * `schema`: the schema config file.
  * `x`, `y`: which covariates should be the x- and y-axes.
  * `tokenizer`: what tokenizer to run. Options are 
    - `StanfordTokenizer`, which is good for traditionally edited text.
    - `SimpleTokenizer`, which tokenizes only on whitespace. If you want to run your own tokenizer, an easy way to use it is to encode your tokenization with spaces between the tokens, and use this. Its tokenization quality is low on real text. But it is fast.
  * `nlp_file`: this is an alternative to `tokenizer`. It says you don't want the application to run any NLP routines, and instead read off all NLP annotations from an external file. It relies on the `id` document identifiers in order to merge the annotations against the text and covariates.  I don't have documentation for the format, but it is produced by [this](https://github.com/brendano/myutil/blob/master/src/corenlp/Parse.java).  Currently this is the only way to get part-of-speech and named entity annotations into the system.

In `schema.conf`, every key is the name of a covariate, and the type is given.  Legal types are

 * `numeric`, for a numeric variable (either integer or floating point is fine).
 * `categ`, a categorical variable (a.k.a. discrete, or what R calls a factor).
   In the data, the values for a categorical variables are represented as
   strings.  In the schema, you can optionally specify a list of possible
   values.  The ordering you give will be the order it is displayed in.  See
   bible.zip for an example.

The format for the config files is a lax form of JSON, described [here](https://github.com/typesafehub/config/blob/master/HOCON.md).  Any legal JSON can be used for the config file; it has a few niceties like being able to sometimes skip quoting, and leaving off commas when using a separate line per entry.  The comment character is `#`.
