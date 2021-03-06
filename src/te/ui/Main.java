package te.ui;

import bibliothek.gui.DockController;
import bibliothek.gui.dock.DefaultDockable;
import bibliothek.gui.dock.SplitDockStation;
import bibliothek.gui.dock.station.split.SplitDockGrid;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import edu.stanford.nlp.util.StringUtils;
import te.data.Analysis.TermvecComparison;
import te.data.*;
import te.exceptions.BadConfig;
import te.exceptions.BadData;
import te.exceptions.BadSchema;
import te.ui.docview.BrushPanel;
import te.ui.docview.DocList;
import te.ui.queries.*;
import te.ui.textview.FullDocViewer;
import te.ui.textview.KWICViewer;
import utility.util.U;

import javax.swing.*;
import javax.swing.JFormattedTextField.AbstractFormatter;
import javax.swing.JFormattedTextField.AbstractFormatterFactory;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/* UI state and event architecture for  [A] <==> [B] <== [C]
 *   A = global state in AllQueries.instance()
 *   B = state in UI components, typically within Swing classes
 *   C = the user, or at least, whatever the low-level user interaction subsystem is deep within swing/awt
 * 
 * [AQ global state]  <== EventBus ==> [UI components] <== Swing events from user actions
 * 
 * there are two different event systems in use:
 *   A <=> B is a Guava EventBus system, that we define here
 *   C  => B is the Swing UI event system
 * 
 * the arrows represent groups of functions that control and move state around.
 * 
 * refresh*() are the entry points from the event notification system. they RECEIVE data from the centralized state.
 *    i.e. A ==> B
 * user*() are entry points from the UI Swing event system, which should be directly, or fairly directly, caused by user actions.
 *   i.e. C ==> B
 * push*() are invoked during the codepath of a UI action. They SEND data into the centralized state.
 *   i.e. B ==> A
 * 
 * ideally the callgraphs of receiving vs sending code pathways should not be mixed, i think?
 * at least, methods that can potentially change UI state.
 * messages that updates are necessary should always? come from the EventBus system.
 * 
 * all UI code should feel free to read global state off the AQ.  (this should simplify code compared to message-passing all information for all state changes.)
 * pushing to AQ, however, right now is centralized in Main.
 * so when UI code is in external files, they callback to Main which then pushes the right info to AQ.
 * 
 * the Guava EventBus is being used in serial mode, which considerably simplifies the logic and reduces race conditions.
 * for better responsiveness during compute-heavy actions we might have to revisit this.
 */

public class Main {
	public Corpus corpus = new Corpus();
	EventBus eventBus = new EventBus();

	String xattr, yattr;

	public List<String> docdrivenTerms = new ArrayList<>();
	public List<String> pinnedTerms = new ArrayList<>();
	public List<String> termdrivenTerms = new ArrayList<>();

	TermvecComparison docvarCompare;
	TermvecComparison termtermBoolqueryCompare;

	JFrame mainFrame;
	TermTable  docdrivenTermTable;
	TermTable pinnedTermTable;
	TermTable  termdrivenTermTable;
	BrushPanel brushPanel;
	DocList doclistPanel;
	KWICViewer kwicPanel;
	FullDocViewer fulldocPanel;
	DefaultDockable fulldocDock;
	InfoArea mainqueryInfo;
	InfoArea subqueryInfo;
	JLabel termlistInfo;
	JSpinner tpSpinner;
	JSpinner tcSpinner;
	InfoArea termtermDescription;

	DocdrivenTermsDock docdrivenTermsDock;

	NLP.DocAnalyzer da = new NLP.UnigramAnalyzer();
	Supplier<Void> afteranalysisCallback = () -> null;
	Supplier<Void> uiOverridesCallback = () -> null;

	//////////////   controller type stuff    ////////////

	/** only call this after schema is set. return false if fails. */
	public boolean setXAttr(String xattrName) {
		if ( ! corpus.getSchema().varnames().contains(xattrName)) {
			return false;
		}
		xattr = xattrName;
		return true;
	}
	/** only call this after schema is set. return false if fails. */
	public boolean setYAttr(String yattrName) {
		if ( ! corpus.getSchema().varnames().contains(yattrName)) {
			return false;
		}
		yattr = yattrName;
		return true;
	}

	double getTermProbThresh() {
		return (double) tpSpinner.getValue();
	}
	int getTermCountThresh() {
		return (int) tcSpinner.getValue();
	}
	AllQueries AQ() { return AllQueries.instance(); }

	void userSelectsTerminstForFullview(Document d, TermInstance ti) {
		userSelectsSingleDocumentForFullview(d);
		AQ().fulldocPanelCurrentDocID = d.docid;
		FulldocChange e = new FulldocChange();
		e.desiredTerminstToScrollTo = ti;
		eventBus.post(e);
	}

	void userSelectsSingleDocumentForFullview(Document doc) {
		AQ().fulldocPanelCurrentDocID = doc.docid;
		FulldocChange e = new FulldocChange();
		eventBus.post(e);
	}

	@Subscribe
	public void refreshFulldoc(FulldocChange e) {
		Document doc = corpus.pullDocument(AQ().fulldocPanelCurrentDocID);
		if (doc == null) return;
		fulldocDock.setTitleText("Document: " + doc.docid);
		fulldocPanel.show(AQ().termQuery().terms, doc);
		if (e.desiredTerminstToScrollTo != null) {
			fulldocPanel.textarea.requestScrollToTerminst(e.desiredTerminstToScrollTo);
		}
	}

	@Subscribe
	public void refreshDocdrivenTermListFromUpdateEvent(DocSelectionChange e) {
		refreshDocdrivenTermList();
	}

	void refreshDocdrivenTermList() {
		// two inputs.  1. docsel according to brush/doc panel.  2. freq thresh spinners.
		DocSet curDS = AQ().curDocs();
		docvarCompare = new TermvecComparison(curDS.terms, corpus.globalTerms);
		docdrivenTerms.clear();
		docdrivenTerms.addAll( docvarCompare.topEpmi(getTermProbThresh(), getTermCountThresh()) );
		docdrivenTermTable.model.fireTableDataChanged();

		termlistInfo.setText(U.sf("%d/%d terms", docdrivenTerms.size(), curDS.terms.support().size()));
		pinnedTermTable.updateCalculations();
//		int effectiveTermcountThresh = (int) Math.floor(getTermProbThresh() * curDS.terms.totalCount);
//		termcountInfo.setText(effectiveTermcountThresh==0 ? "all terms" : U.sf("count >= %d", effectiveTermcountThresh));
	}

	void runTermTermQuery(TermQuery tq) {
		// bool-occur
		TermVector focus = corpus.select(tq.terms).terms;
		termtermBoolqueryCompare = new TermvecComparison(focus, corpus.globalTerms);
		termdrivenTerms = termtermBoolqueryCompare.topEpmi(getTermProbThresh(), getTermCountThresh());
		termdrivenTermTable.model.fireTableDataChanged();
		String queryterms = tq.terms.stream().collect(Collectors.joining(", "));
		String queryinfo = U.sf("%d %s: %s", tq.terms.size(), tq.terms.size()==1 ? "term" : "terms", queryterms);
		termtermDescription.setText(U.sf("Terms most associated with %s", queryinfo));
		termtermDescription.setToolTipText(queryinfo);

		// joint- or cond-occur
//		Analysis.TermTermAssociations tta = new Analysis.TermTermAssociations();
//		tta.queryTerms = tq.terms;
//		tta.corpus = corpus;
//		List<String> termResults = tta.topEpmi(1);
	}

	void pushTermQueryChange() {
		AQ().setTermQuery( getCurrentTQFromUIState() );
		eventBus.post(new TermQueryChange());
	}

	@Subscribe
	public void refreshFromNewTermquery(TermQueryChange e) {
		TermQuery curTQ = AQ().termQuery();
		String msg = curTQ.terms.size()==0 ? "No selected terms"
				: curTQ.terms.size()+" selected terms: " + StringUtils.join(curTQ.terms, ", ");
		subqueryInfo.setText(msg);
		runTermTermQuery(curTQ);
		// these dont need to be explicitly called in the refresh pubsub framework
		// but keep comments here so we know we need to single them out for subscriptions to termdrivenquery changes
//		refreshKWICPanel();
//		refreshSingleDocumentInFullview();
	}

	@Subscribe public void refreshFulldocPanel(FulldocChange e) { refreshFulldocPanel(); }
	@Subscribe public void refreshFulldocPanel(TermQueryChange e) { refreshFulldocPanel(); }
	public void refreshFulldocPanel() {
		fulldocPanel.showForCurrentDoc(AQ().termQuery().terms, false);
	}

	@Subscribe
	public void refreshQueryInfoPanel(AllQueryChange e) {
		DocSet cd = AQ().curDocs();
		String s = U.sf("Docvar selection: %s docs, %s wordtoks",
				GUtil.commaize(cd.docs().size()),
				GUtil.commaize((int) cd.terms.totalCount));
		mainqueryInfo.setText(s);
	}

	void pushUpdatedDocSelectionFromDocPanel(Collection<String> docids) {
		Set<String> s = new HashSet<>(docids);
		boolean same = AQ().docPanelSelectedDocIDs.equals(s);
		if (!same) {
			AQ().docPanelSelectedDocIDs = new HashSet<>(docids);
			eventBus.post(new DocSelectionChange());
		}
	}

	TermQuery getCurrentTQFromUIState() {
		TermQuery curTQ = new TermQuery(corpus);
		Set<String> selterms = new LinkedHashSet<>();
		selterms.addAll(docdrivenTermTable.getSelectedTerms());
		selterms.addAll(pinnedTermTable.getSelectedTerms());
		curTQ.terms.addAll(selterms);
//    	U.pf("curTQ %s\n", curTQ);
		return curTQ;
	}

	void addTermdriverAction(final TermTable tt) {
		tt.table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				pushTermQueryChange();
			}});
	}

	void pinTerm(String term) {
		if ( ! pinnedTerms.contains(term)) {
			pinnedTerms.add(term);
//			pinnedTermTable.model.fireTableRowsInserted(pinnedTerms.size()-2, pinnedTerms.size()-1);
			pinnedTermTable.model.fireTableRowsInserted(0, pinnedTerms.size()-1);
//			pinnedTermTable.table.setRowSelectionInterval(pinnedTerms.size()-1, pinnedTerms.size()-1);
			pushTermQueryChange();
		}
	}
	void unpinTerm(String term) {
		int[] rowsToDel = IntStream.range(0,pinnedTermTable.model.getRowCount())
				.filter(row -> pinnedTermTable.getTermAt(row).equals(term))
				.toArray();
		for (int row : rowsToDel) {
			pinnedTermTable.model.fireTableRowsDeleted(row,row);
		}
		pinnedTerms.remove(term);
		pushTermQueryChange();
	}


	void setupTermfilterSpinners() {
		tpSpinner = new JSpinner(new SpinnerStuff.MySpinnerModel());
		tpSpinner.setToolTipText("Minimum term frequency in units of Words Per Million.");
		JFormattedTextField tpText = ((JSpinner.DefaultEditor) tpSpinner.getEditor()).getTextField();
		tpText.setFormatterFactory(new AbstractFormatterFactory() {
			@Override public AbstractFormatter getFormatter(JFormattedTextField tf) {
				return new SpinnerStuff.WPMFormatter();
			}
		});
		tpText.setEditable(true);
		tpSpinner.setMinimumSize(new Dimension(20,-1));
		tpSpinner.setValue(300 / 1e6);
		tpSpinner.addChangeListener(e -> {
			refreshDocdrivenTermList();
			eventBus.post(new AllQueryChange());  // todo should exclude docdriventermlist
		});

		tcSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 1000, 1));
		tcSpinner.setValue(1);  // should be 2 usually, i think?
		tcSpinner.addChangeListener(e -> {
			refreshDocdrivenTermList();
			eventBus.post(new AllQueryChange());  // todo should exclude docdriventermlist
		});
		tcSpinner.setToolTipText("Minimum term count (number of occurrences).");
	}


	//////////////////////  main setup method   ////////////////////////////////////////////////////////////////////////////


	void setupUI() {
		AQ().corpus = this.corpus;
		eventBus.register(this);

		/////////////////  termpanel  ///////////////////

		setupTermfilterSpinners();

		JPanel termfilterPanel = new JPanel();
		termfilterPanel.setLayout(new BoxLayout(termfilterPanel, BoxLayout.X_AXIS));
		termfilterPanel.add(new JLabel("Term WPM >=") {{ setFont(new Font("SansSerif",Font.PLAIN,10)); }});
		termfilterPanel.add(tpSpinner);
//        termfilterPanel.add(new JLabel("   "));
//        termfilterPanel.add(new JLabel("Count >=") {{ setFont(new Font("SansSerif",Font.PLAIN,9)); }});
//        termfilterPanel.add(tcSpinner);
		tpSpinner.setMinimumSize(new Dimension(100,30));
		tpSpinner.setPreferredSize(new Dimension(100,30));
		tcSpinner.setMinimumSize(new Dimension(60,30));
		tcSpinner.setMaximumSize(new Dimension(60,30));

		termlistInfo = new JLabel();

		//////  termtable: below the frequency spinners  /////

		docdrivenTermTable = new TermTable(new TermTableModel());
		docdrivenTermTable.model.terms = () -> docdrivenTerms;
		docdrivenTermTable.model.comparison = () -> docvarCompare;
		docdrivenTermTable.setupTermTable();
		addTermdriverAction(docdrivenTermTable);
		docdrivenTermTable.doubleClickListener = this::pinTerm;

		termdrivenTermTable = new TermTable(new TermTableModel());
		termdrivenTermTable.model.terms = () -> termdrivenTerms;
		termdrivenTermTable.model.comparison = () -> termtermBoolqueryCompare;
		termdrivenTermTable.setupTermTable();
		termdrivenTermTable.doubleClickListener = this::pinTerm;

		pinnedTermTable = new TermTable(new TermTableModel());
		pinnedTermTable.model.terms = () -> pinnedTerms;
		pinnedTermTable.model.comparison = () -> docvarCompare;
		pinnedTermTable.setupTermTable();
		addTermdriverAction(pinnedTermTable);
		pinnedTermTable.doubleClickListener = this::unpinTerm;

		JPanel pinnedWrapper = new JPanel(new BorderLayout());
		pinnedWrapper.add(pinnedTermTable.top(), BorderLayout.CENTER);

		JPanel docdrivenTermsWrapper = new JPanel(new BorderLayout());
		JPanel toptop = GUtil.emptyPanel();
		toptop.setLayout(new BoxLayout(toptop,BoxLayout.Y_AXIS));
		JPanel topstuff = GUtil.emptyPanel();
		topstuff.setLayout(new FlowLayout(FlowLayout.LEFT));
		topstuff.add(termfilterPanel);
		topstuff.add(termlistInfo);
		toptop.add(topstuff);
		docdrivenTermsWrapper.add(toptop, BorderLayout.NORTH);
		docdrivenTermsWrapper.add(docdrivenTermTable.top(), BorderLayout.CENTER);

		termtermDescription = new InfoArea("");
		JPanel termdrivenWrapper = new JPanel(new BorderLayout()) {{
			addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(ComponentEvent e) {
//        			U.p("termterm " + sizes(termtermDescription));
				}
			});
		}};
		termdrivenWrapper.add(termtermDescription, BorderLayout.NORTH);
		termdrivenWrapper.add(termdrivenTermTable.top(), BorderLayout.CENTER);

		//////////////////////////  right-side panel  /////////////////////////

		mainqueryInfo = new InfoArea("");
		subqueryInfo = new InfoArea("");

		JPanel queryInfo = new JPanel() {{
			setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
			add(mainqueryInfo); add(subqueryInfo);
			addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(ComponentEvent e) {
//	        			U.p("mainqueryInfo " + sizes(mainqueryInfo));
//	        			U.p("subqueryInfo " + sizes(subqueryInfo));
				}
			});
		}
		};

		brushPanel = new BrushPanel(this::pushUpdatedDocSelectionFromDocPanel, corpus.allDocs());
		brushPanel.schema = corpus.getSchema();
		// todo this is bad organization that the app owns the xattr/yattr selections and copies them to the brushpanel, right?
		// i guess eventually we'll need a current-user-config object as the source of truth for this and brushpanel should be hooked up to pull from it?
		if (xattr != null) brushPanel.xattr = xattr;
		if (yattr != null) brushPanel.yattr = yattr;
		brushPanel.setDefaultXYLim(corpus);
		eventBus.register(brushPanel);

		doclistPanel = new DocList(this::pushUpdatedDocSelectionFromDocPanel, new ArrayList<>(corpus.allDocs()));
		doclistPanel.fulldocClickReceiver = this::userSelectsSingleDocumentForFullview;
		eventBus.register(doclistPanel);

		kwicPanel = new KWICViewer();
		kwicPanel.fulldocClickReceiver = this::userSelectsSingleDocumentForFullview;
		kwicPanel.fulldocTerminstClickReceiver = this::userSelectsTerminstForFullview;
		eventBus.register(kwicPanel);

		fulldocPanel = new FullDocViewer();

		DockController controller = new DockController();
		SplitDockStation station = new SplitDockStation();
		controller.add(station);

		SplitDockGrid grid = new SplitDockGrid();

		fulldocDock = new DefaultDockable("Document view") {{ add(fulldocPanel.top()); }};
		docdrivenTermsDock = new DocdrivenTermsDock(this);
		docdrivenTermsDock.add(docdrivenTermsWrapper);
		docdrivenTermsDock.setTitleText("Document-associated terms");
		eventBus.register(docdrivenTermsDock);

//		double x=0.5, rx=1-0.5;
		double w1=3, w2=6;
		double y,h;
		y=0;
		grid.addDockable(0,0,   w1,h=5, new DefaultDockable("Pinned terms") {{ add(pinnedWrapper); }});
//		grid.addDockable(0,y+=h, w1,h=2, new DefaultDockable("Frequency control") {{ add(termfilterPanel); }});
		grid.addDockable(0,y+=h, w1,h=15, docdrivenTermsDock);
//		grid.addDockable(0,y+=h, w1,h=5, new DefaultDockable("Term-associated terms") {{ add(termdrivenWrapper); }});
//		grid.addDockable(0,y+=h, x,8, fulldocDock);

		y=0;
		grid.addDockable(w1,y, w2,h=3, new DefaultDockable("Query info") {{ add(queryInfo); }});
		y+=h;
		h=7;
		grid.addDockable(w1,y, w2,h, new DefaultDockable("Doc Covariates") {{ add(brushPanel); }});
		grid.addDockable(w1,y, w2,h, new DefaultDockable("Documents") {{ add(doclistPanel.top()); }});
		y += h;
		h=15;
		grid.addDockable(w1, y,           w2/2, h, new DefaultDockable("KWIC view") {{ add(kwicPanel.top()); }});
		grid.addDockable(w1+w2/2, y, w2/2, h, fulldocDock);

		station.dropTree(grid.toTree());

		mainFrame = new JFrame("Text Explorer Tool");
		mainFrame.add(station.getComponent());
		mainFrame.pack();
		mainFrame.setBounds(15,0, 1000, 768-25);  //  mac osx toolbar is 22,23ish tall
		mainFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		ToolTipManager.sharedInstance().setDismissDelay((int) 1e6);
	}

	/////////////  startup initialization stuff   /////////////////////////////////////////////////////////////////////

	static void usage() {
		System.out.println("Usage:  Launch ConfigFilename");
		System.exit(1);
	}

	void finalizeCorpusAnalysisAfterConfiguration() {
		long t0=System.nanoTime();
		U.p("Analyzing covariates");

		if (corpus.needsCovariateTypeConversion) {
			corpus.convertCovariateTypes();
		}
		corpus.calculateCovariateSummaries();

		U.pf("done analyzing covariates (%.0f ms)\n", 1e-6*(System.nanoTime()-t0));
		t0=System.nanoTime(); U.p("Analyzing document texts");

		for (Document doc : corpus.allDocs()) {
			if (Thread.interrupted()) return;
			NLP.analyzeDocument(da, doc);
		}
		afteranalysisCallback.get();

		U.pf("done analyzing doc texts (%.0f ms)\n", 1e-6*(System.nanoTime()-t0));

		corpus.finalizeIndexing();
	}
	static FileSystem FS = FileSystems.getDefault();

	void initializeFromCommandlineArgs(String args[]) throws IOException, BadConfig, BadSchema, BadData {

		boolean gotConfFile = false;
		Configuration c = null;
		DataLoader dataloader = new DataLoader();

		for (String arg : args) {
			Path p = FS.getPath(arg);
//			U.pf("%s  isfile %s  isdir %s\n", arg, Files.isRegularFile(p), Files.isDirectory(p));
			if (Files.isDirectory(p)) {
				dataloader.loadTextFilesFromDirectory(arg);
			} else if (Files.isRegularFile(p)) {
				if (arg.matches(".*\\.(conf|config)$")) {
					if (gotConfFile) {
						assert false : "more than one configuration file specified";
					}
					U.pf("Processing as config file: %s\n", arg);
					gotConfFile = true;
					c = new Configuration();
					c.initWithConfig(this, arg, dataloader);
				} else if (arg.endsWith(".txt")) {
					dataloader.loadTextFileAsDocumentText(arg);
				}
			} else {
				U.p("WARNING: can't handle argument: " + arg);
			}
		}
		corpus.setDataFromDataLoader(dataloader);
		if (c==null) {
			c = Configuration.defaultConfiguration(this);
		}
		c.doNLPBasedOnConfig();
	}
	public static void myMain(String[] args) throws Exception {
		long t0=System.nanoTime();
		final Main main = new Main();

		if (args.length < 1) usage();

		main.initializeFromCommandlineArgs(args);
		main.finalizeCorpusAnalysisAfterConfiguration();

		SwingUtilities.invokeLater(() -> {
			main.setupUI();
			main.uiOverridesCallback.get();
			main.mainFrame.setVisible(true);
			U.pf("UI ready (%.1f ms)\n", 1e-6 * (System.nanoTime() - t0));
		});
	}
}
