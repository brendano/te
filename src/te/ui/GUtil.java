package te.ui;

import te.data.Span;
import utility.util.U;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GUtil {
	
	public static JPanel emptyPanel() {
		JPanel p =new JPanel();
		p.setBorder(new EmptyBorder(0,0,0,0) );
		return p;
	}

	/// http://colorbrewer2.org/
	
	public static Color[] Dark2 = new Color[] {
			new Color(27,158,119), //teal
			new Color(217,95,2), //orangebrown
			new Color(117,112,179), //purpley
			new Color(231,41,138), //magentapink
			new Color(102,166,30), //green
			new Color(230,171,2),  //tan
			new Color(166,118,29),  //brown
			new Color(102,102,102)  //darkgray
	};
	
	public static Color[] Set1 = new Color[] {
		new Color(228,26,28), //red
		new Color(55,126,184), //blue
		new Color(77,175,74), //green
		new Color(152,78,163), //purple
		new Color(255,127,0),  //orange
		new Color(255,255,51),  //yellow
		new Color(166,86,40),  //brown
		new Color(247,129,191), //pink
		new Color(153,153,153)  //gray
	};

	public static void drawLine(Graphics2D g, double x1, double y1, double x2, double y2) {
		g.drawLine( (int) x1, (int) y1, (int) x2, (int) y2);
	}

	/** 
	 * centered: adjx,adjy = (0,0)
	 * right-aligned (flush left of the point): adjx=-1
	 * left-aligned (flush the right of the point): adjx=1
	 * top-aligned (flush below the point): adjy=1
	 * bottom-aligned (flush above the point): adjy=-1
	 * 
	 * i'm not sure that top/bottom alignment are working correctly
	 */
	public static void drawCenteredString(Graphics2D g, String s, double x, double y, double adjx, double adjy) {
		Rectangle2D r = g.getFontMetrics().getStringBounds(s, g);
		double finalx = x + r.getWidth() * (adjx-1)/2;
		double finaly = y + r.getHeight() * (adjy+1)/2;
		g.drawString(s, (float) finalx, (float) finaly); 
	}

	public static void drawCenteredCircle(Graphics2D g, double x, double y, double radius, boolean fill) {
		Ellipse2D.Double circle = new Ellipse2D.Double(x -radius, y -radius, 2*radius, 2*radius);
		if (fill) {
			g.fill(circle);
		} else {
			g.draw(circle);	
		}
	}
	static int roundInt(double x) {
		return (int) Math.round(x);
	}
	public static void drawCenteredTriangle(Graphics2D g, double x, double y, double radius, boolean fill) {
		// w,h are the half-width and half-height of the equilateral triangle, respectively
		// http://brenocon.com/Screen%20Shot%202014-03-19%20at%203.35.50%20PM.jpg
		
		radius *= 2;
		double w = radius * Math.cos(Math.PI/6);
		double h = radius * Math.sin(Math.PI/6);
		double z = 0.8;  // horiz multiplier
		// botleft, topctr, botright
		int[] xs = new int[]{ roundInt(x-w*z), roundInt(x), roundInt(x+w*z) };
		int[] ys = new int[]{ roundInt(y+h), roundInt(y-h), roundInt(y+h) };
		if (fill) {
			g.fillPolygon(xs,ys,xs.length);
		} else {
			g.drawPolygon(xs,ys,xs.length);
		}
	}
	
	static boolean isInteger(double x) {
		return Math.abs(x - Math.round(x)) < 1e-100;
	}
	/** grid125(.001, 1) => [0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0] */
	public static List<Double> logGrid125(double min, double max) {
		assert min <= max;
		List<Double> ret = new ArrayList<>();
		double cur = min;
		while (cur <= max) {
			ret.add(cur);
			if (isInteger(Math.log10(Math.abs(cur)))) {
				cur *= 2;
			}
			else if (isInteger(Math.log10(Math.abs(cur/2)))) {
				double base = cur/2;
				cur = base*5;
			}
			else if (isInteger(Math.log10(Math.abs(cur/5)))) {
				cur *= 2;
			}
		}
		return ret;
	}

	public static List<Double> logGrid1s(double min, double max) {
		assert min <= max;
		List<Double> ret = new ArrayList<>();
		double curbase = Math.pow(10, Math.floor(Math.log10(min)));
		double curmult = min/curbase;
//		U.p(curbase + " " + curmult);
		while ( (curbase*curmult) <= max) {
			ret.add( curbase*curmult );
			if (curmult==10) {
				curbase *= 10;
				curmult=0;
			}
			else {
				curmult++;	
			}
		}
		return ret;
	}

	//	public static void main(String[] args) {
	//		U.p(logGrid125( Double.parseDouble(args[0]), Double.parseDouble(args[1]) ));
	//		U.p(logGrid1s( Double.parseDouble(args[0]), Double.parseDouble(args[1]) ));
	//	}

	public static String commaize(int x) {
		if (Math.abs(x) < 1e4) return U.sf("%d", x);
		return NumberFormat.getNumberInstance(Locale.US).format(x);	
	}
	public static String commaize(double x) {
		if (Math.abs(x) < 1e4) return U.sf("%f", x);
		return NumberFormat.getNumberInstance(Locale.US).format(x);	
	}

	public static double getRenderedTextWidth(Graphics2D g, String text) { 
		return g.getFontMetrics().stringWidth(text);
	}
	

	/** like {@link java.util.Map#putIfAbsent} except instantiates the value if it's needed. */
	public static <K,V> void ensureValue(Map<K,V> map, K key, Supplier<V> fn) {
		if (!map.containsKey(key)) {
			map.put(key, fn.get());
		}
	}
	/** if key not in map, add it with a new arraylist value */
	public static <K,V> void ensureList(Map<K, List<V>> map, K key) {
		ensureValue(map, key, ()-> new ArrayList<V>());
	}
	/** if key not in map, add it with a new hashmap value */
	public static <K,K2,V> void ensureMap(Map<K, Map<K2,V>> map, K key) {
		ensureValue(map, key, ()-> new HashMap<K2,V>());
	}

	public static String substring(String str, Span charspan) {
		return str.substring(charspan.start, charspan.end);
	}

	/** regex "-" on string a-bb-c- ==> [0,1), [2,4), [5,6), [7,7) */
	public static List<Span> splitIntoSpans(String regex, String text) {
		List<Span> spans = new ArrayList<>();
		int curstart=0;
		Matcher m = Pattern.compile(regex).matcher(text);
		while (m.find()) {
			spans.add(new Span(curstart, m.start()));
			curstart=m.end();
		}
		spans.add(new Span(curstart, text.length()));
		return spans;
	}
	/** 0, {4,8}, 10 ===> [0,4), [4,8), [8,10) */  
	public static List<Span> breakpointsToSpans(int start, List<Integer> breakpoints, int end) {
		List<Span> spans = new ArrayList<>();
		if (end < start) return spans;
		if (start==end && breakpoints.size()>0) return spans;  // not clear what the right behavior is
		// if end>last breakpoint ... also not clear what the right behavior is
		
		int curstart=start;
		for (int p : breakpoints) {
			spans.add(new Span(curstart, p));
			curstart=p;
		}
		spans.add(new Span(curstart, end));
		return spans;
	}
	
	public static void main(String[] args) {
		String text = "asdf\nqwer";
		int curstart=0;
		Matcher m = Pattern.compile("(?=\n)").matcher(text);
		while (m.find()) {
			U.p(new Span(curstart, m.start()));
			curstart=m.end();
		}
		U.p(new Span(curstart, text.length()));
	}

	public static int bounded(int x, int min, int max) {
		if (x<min) return min;
		if (x>max) return max;
		return x;
	}
	public static double bounded(double x, double min, double max) {
		if (x<min) return min;
		if (x>max) return max;
		return x;
	}

	/** for inc-exc spans ... is [s1,e1) SUBSETEQ [s2,e2)  ? */
	public static boolean spanContainedIn(Span span1, Span span2) {
		return spanContainedIn(span1.start, span1.end, span2.start, span2.end);
	}
	/** for inc-exc spans ... is [s1,e1) SUBSETEQ [s2,e2)  ? */
	public static boolean spanContainedIn(int s1, int e1, Span charspan) {
		return spanContainedIn(s1,e1, charspan.start, charspan.end);
	}
	/** for inc-exc spans ... is [s1,e1) SUBSETEQ [s2,e2)  ? */
	public static boolean spanContainedIn(int s1, int e1, int s2, int e2) {
		assert s1<=e1 && s2<=e2;
		return (s1>=s2 && e1<=e2);
	}
	public static boolean spansIntersect(Span span1, Span span2) {
		return spansIntersect(span1.start, span1.end, span2.start, span2.end);
	}
	public static boolean spansIntersect(Span span1, int start2, int end2) {
		return spansIntersect(span1.start, span1.end, start2, end2);
	}
	/** for inc-exc spans */
	public static boolean spansIntersect(int s1, int e1, int s2, int e2) {
		assert s1<=e1 && s2<=e2;
		return	
					(s1 <= s2 && s2 < e1) ||
					(s2 <= s1 && s1 < e2);
	}
	
	///////////// really misc stuff //////////////
	
	/** returns true if o1 and o2 are both nonnull and equal 
	 * 
	 * reason this exists: o1.equals(o2) crashes if o1 is null.  and usually null means some weird exceptional case
	 * or that the data isn't set to anything yet, so getting that two things are both null usually means something
	 * quite different compared to two non-null variables having the same value.
	 */
	public static boolean nonnullEqual(Object o1, Object o2) {
		return o1!=null && o2!=null && o1.equals(o2);
	}
}
