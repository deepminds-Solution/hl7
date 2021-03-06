package hu;

import hu.mv.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.*;

import ca.uhn.hl7v2.*;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.Initiator;
import ca.uhn.hl7v2.model.*;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.GenericModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/** utilities for hl7 messages */
public class MsgUtil {
	
	public static final String HIGHEST_VERSION = "Highest";
	public static final String DEFAULT_VERSION = "Default";
	public static final String GENERIC_VERSION = "Generic";
	
	private static final Properties messages = new Properties();
	private static final Properties segments = new Properties();
	private static final Map<String, DefaultHapiContext> contexts = new TreeMap<>();
	
	static {
		try (InputStream is = MsgUtil.class.getResourceAsStream("/messages.txt")) {
			messages.load(is);
		} catch (Exception e) {
			e.printStackTrace();
		}
		try (InputStream is = MsgUtil.class.getResourceAsStream("/segments.txt")) {
			segments.load(is);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private MsgUtil () {
		//
	}
	
	public static List<String> getVersions () {
		List<String> versions = new ArrayList<>();
		versions.add(MsgUtil.HIGHEST_VERSION);
		versions.add(MsgUtil.DEFAULT_VERSION);
		versions.add(MsgUtil.GENERIC_VERSION);
		for (Version v : Version.availableVersions()) {
			versions.add(v.getVersion());
		}
		return versions;
	}
	
	public static Message send (Message msg, String version, String host, int port) throws Exception {
		DefaultHapiContext context = getContext(version);
		Connection connection = context.newClient(host, port, false);
		Initiator initiator = connection.getInitiator();
		Message response = initiator.sendAndReceive(msg);
		return response;
	}
	
	public static boolean equals (Message msg1, Message msg2) throws Exception {
		return msg1.encode().equals(msg2.encode());
	}
	
	public static DefaultHapiContext getContext (String version) {
		DefaultHapiContext c = contexts.get(version);
		if (c == null) {
			switch (version) {
				case DEFAULT_VERSION:
					c = new DefaultHapiContext();
					break;
				case GENERIC_VERSION:
					c = new DefaultHapiContext(new GenericModelClassFactory());
					break;
				case HIGHEST_VERSION:
					c = getContext(Version.latestVersion().getVersion());
					break;
				default:
					c = new DefaultHapiContext(new CanonicalModelClassFactory(version));
					break;
			}
			c.setValidationContext(ValidationContextFactory.noValidation());
			contexts.put(version, c);
		}
		return c;
	}
	
	/** get info about the message and the index */
	public static MsgInfo getInfo (final String msgCr, final String version) throws Exception {
		System.out.println("get info");
		// parse the message
		final HapiContext context = getContext(version);
		final PipeParser p = context.getPipeParser();
		final Message msg = p.parse(msgCr);
		final Terser terser = new Terser(msg);
		final MsgSep sep = new MsgSep(msgCr);
		System.out.println("sep: " + sep);
		
		for (String s : msg.getNames()) {
			System.out.println("name=" + s + " required=" + msg.isRequired(s) + " group=" + msg.isGroup(s) + " repeating=" + msg.isRepeating(s));
		}
		
		return new MsgInfo(msg, terser, sep, msgCr);
	}
	
	public static List<Comment> getErrors (Message msg, String msgCr, MsgSep sep, String msgVersion, String selectedValue) throws Exception {
		switch (msgVersion) {
			case DEFAULT_VERSION:
			case GENERIC_VERSION:
				msgVersion = msg.getVersion();
				break;
			case HIGHEST_VERSION:
				msgVersion = Version.latestVersion().getVersion();
				break;
			default:
				break;
		}
		ValidatingMessageVisitor vmv = new ValidatingMessageVisitor(msgCr, sep, msgVersion, selectedValue);
		MessageVisitors.visit(msg, vmv);
		return vmv.getComments();
	}
	
	/** get the terser path for the message position */
	public static MsgPath getTerserPath (final Message msg, final Terser terser, final MsgPos pos, String descSep) throws Exception {
		MsgSeg sl = getSegment(msg, pos.segOrd);
		StringBuilder pathSb = new StringBuilder();
		String desc = "";
		String value = "";
		String path = "";
		
		if (sl != null) {
			pathSb.append(sl.location.toString());
			if (pos.fieldOrd > 0) {
				pathSb.append("-" + pos.fieldOrd);
				if (pos.fieldRep > 0) {
					pathSb.append("(" + pos.fieldRep + ")");
				}
				if (pos.compOrd > 1 || pos.subCompOrd > 1) {
					pathSb.append("-" + pos.compOrd);
					if (pos.subCompOrd > 1) {
						pathSb.append("-" + pos.subCompOrd);
					}
				}
			}
			path = pathSb.toString();
			desc = getDescription(sl.segment, pos, descSep).toString();
			
			if (pos.fieldOrd > 0) {
				try {
					value = terser.get(path);
					
				} catch (Exception e) {
					System.out.println("could not get value of terser path: " + e);
					desc = e.toString();
				}
			}
		}
		
		return new MsgPath(path, value, desc);
	}
	
	/** get position of segment path, never returns null */
	public static MsgPos getPosition (Terser terser, String path) throws Exception {
		Segment segment = terser.getSegment(path.substring(0, path.indexOf("-")));
		int[] i = Terser.getIndices(path);
		int s = getSegmentOrdinal(segment.getMessage(), segment);
		return new MsgPos(s, i[0], i[1], i[2], i[3]);
	}
	
	/** get segment ordinal of segment */
	public static int getSegmentOrdinal (final Message msg, final Segment findSegment) throws Exception {
		//System.out.println("get segment ordinal of " + findSegment);
		final int[] segOrd = new int[1];
		
		final MessageVisitorAdapter mv = new MessageVisitorAdapter() {
			int s = 1;
			
			@Override
			public boolean start2 (AbstractSegment segment, Location location) throws HL7Exception {
				//System.out.println("segment=" + segment + " s=" + s);
				if (!segment.isEmpty()) {
					if (findSegment == segment) {
						//System.out.println("found");
						segOrd[0] = s;
						continue_ = false;
					}
					s++;
				}
				return continue_;
			}
		};
		
		MessageVisitors.visit(msg, MessageVisitors.visitStructures(mv));
		
		if (segOrd[0] == 0) {
			System.out.println("could not get segment ordinal of " + findSegment);
		}
		
		return segOrd[0];
	}
	
	/** get segment and segment location from a segment ordinal */
	public static MsgSeg getSegment (final Message msg, final int segmentOrd) {
		final MsgSeg[] sl = new MsgSeg[1];
		MessageVisitorAdapter mv = new MessageVisitorAdapter() {
			int s = 1;
			
			@Override
			public boolean start2 (AbstractSegment segment, Location location) throws HL7Exception {
				if (s == segmentOrd) {
					sl[0] = new MsgSeg(segment, location);
					continue_ = false;
				}
				s++;
				return continue_;
			}
		};
		try {
			MessageVisitors.visit(msg, MessageVisitors.visitStructures(mv));
		} catch (HL7Exception e) {
			System.out.println("could not get segment name: " + e);
		}
		return sl[0];
	}
	
	/** get description of hl7 field, component and subcomponent */
	public static String getDescription (final Message msg, final MsgPos pos, final String descSep) {
		MsgSeg sl = getSegment(msg, pos.segOrd);
		return getDescription(sl.segment, pos, descSep).toString();
	}
	
	/** get description of hl7 field, component and subcomponent */
	public static StringBuilder getDescription (final Segment segment, final MsgPos pos, final String sep) {
		System.out.println("get description " + pos);
		final Message msg = segment.getMessage();
		final String msgType = msg.getName().substring(0, 3);
		final String msgTypeDesc = messages.getProperty(msgType, "unknown");
		final String segDesc = segments.getProperty(segment.getName(), "unknown");
		
		final StringBuilder sb = new StringBuilder();
		sb.append("Message: " + msgTypeDesc + " [" + msg.getName() + "]");
		sb.append(sep).append("Segment " + segment.getName() + ": " + segDesc);
		
		final Method fieldMethod = getMethod(segment.getClass(), pos.fieldOrd);
		if (fieldMethod == null) {
			if (pos.fieldOrd > 0) {
				sb.append(sep).append("Field " + pos.fieldOrd + ": Unknown");
			}
			return sb;
		}
		
		String fieldName = fieldMethod.getName().substring(3);
		// see if hapi has a proper field name
		final String[] segNames = segment.getNames();
		if (segNames != null && segNames.length >= pos.fieldOrd && segNames[pos.fieldOrd - 1] != null) {
			fieldName = segNames[pos.fieldOrd - 1];
		}
		final Class<?> fieldType = fieldMethod.getReturnType();
		sb.append(sep).append("Field " + pos.fieldOrd + ": " + fieldName + " [" + fieldType.getSimpleName() + "]");
		
		Method compMethod = getMethod(fieldType, pos.compOrd);
		if (compMethod == null) {
			if (pos.compOrd > 1) {
				sb.append(sep).append("Component " + pos.compOrd + ": unknown");
			}
			if (pos.subCompOrd > 1) {
				sb.append(sep).append("Subcomponent " + pos.compOrd + ": unknown");
			}
			return sb;
		}
		
		final String compName = compMethod.getName().substring(3);
		final Class<?> compType = compMethod.getReturnType();
		sb.append(sep).append("Component " + pos.compOrd + ": " + compName + " [" + compType.getSimpleName() + "]");
		
		Method subCompMethod = getMethod(compType, pos.subCompOrd);
		if (subCompMethod == null) {
			if (pos.subCompOrd > 1) {
				sb.append(sep).append("Subcomponent " + pos.subCompOrd + ": unknown");
			}
			return sb;
		}
		
		final Class<?> subCompType = subCompMethod.getReturnType();
		final String subCompName = subCompMethod.getName().substring(3);
		sb.append(sep).append("Subcomponent " + pos.subCompOrd + ": " + subCompName + " [" + subCompType.getSimpleName() + "]");
		
		return sb;
	}
	
	/**
	 * get method from class, method always returns non void and may take one
	 * parameter for repetition
	 */
	private static Method getMethod (final Class<?> type, final int ord) {
		// getObr5_PriorityOBR()
		// getXcn13_IdentifierTypeCode()
		// getPv125_ContractEffectiveDate()
		Pattern pat = Pattern.compile("get\\w{" + type.getSimpleName().length() + "}(\\d+)_(\\w+)");
		for (Method method : type.getMethods()) {
			Class<?> rt = method.getReturnType();
			if (rt != null && !rt.isPrimitive() && !rt.isArray()) {
				Matcher mat = pat.matcher(method.getName());
				if (mat.matches()) {
					final int index = Integer.parseInt(mat.group(1));
					final String name = mat.group(2);
					if (index == ord) {
						try {
							return type.getMethod("get" + name, method.getParameterTypes());
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
				}
			}
		}
		return null;
	}
	
	/** get the segment, field etc for the character index in the message */
	public static MsgPos getPosition (final String msgstrCr, final MsgSep sep, final int index) {
		// start field at 1 for MSH, 0 for others
		int s = 1, f = 1, fr = 0, c = 1, sc = 1;
		for (int i = 0; i < index; i++) {
			char ch = msgstrCr.charAt(i);
			if (ch == MsgSep.SEGMENT) {
				s++;
				f = 0;
				fr = 0;
				c = 1;
				sc = 1;
			} else if (ch == sep.field) {
				f++;
				fr = 0;
				c = 1;
				sc = 1;
			} else if (ch == sep.repetition) {
				fr++;
				c = 1;
				sc = 1;
			} else if (ch == sep.component) {
				c++;
				sc = 1;
			} else if (ch == sep.subcomponent) {
				sc++;
			}
		}
		
		return new MsgPos(s, f, fr, c, sc);
	}
	
	/** get the character indexes (start and end) of the given logical position, returns null otherwise */
	public static int[] getIndexes (final String msgCr, final MsgSep sep, final MsgPos pos) {
		//System.out.println("get indexes: " + msgCr.length() + ", " + pos);
		
		final int[] indexes = new int[2];
		
		// field and repetition are prefixed (with ~ and |) so start at 0
		// segment, component and subcomponent may not be prefixed (with CR, ^
		// or &) so start at 1
		int s = 1, f = 0, r = 0, c = 1, sc = 1, len = 0;
		
		boolean found = false;
		
		for (int i = 0; i < msgCr.length(); i++) {
			char ch = msgCr.charAt(i);
			
			// System.out.println(String.format("getIndexes: ch %x s %d f %d fr %d c %d sc %d len %d",
			// (int) ch, s, f, r, c, sc, len));
			
			if (s == pos.segOrd && f == pos.fieldOrd && r == pos.fieldRep && c == pos.compOrd && sc == pos.subCompOrd) {
				// System.out.println("matched");
				if (!found) {
					indexes[0] = i;
					indexes[1] = i;
					found = true;
					
				} else {
					indexes[1] = indexes[0] + len;
				}
				
			} else if (found || s > pos.segOrd) {
				break;
			}
			
			if (i == 4 && msgCr.startsWith("MSH")) {
				// special case, MSH-2 always begins at index 4
				f++;
				
			} else {
				if (ch == MsgSep.SEGMENT) {
					s++;
					f = 0;
					r = 0;
					c = 1;
					sc = 1;
					len = 0;
					
				} else if (ch == sep.field) {
					f++;
					r = 0;
					c = 1;
					sc = 1;
					len = 0;
					
				} else if (ch == sep.repetition) {
					r++;
					c = 1;
					sc = 1;
					len = 0;
					
				} else if (ch == sep.component) {
					c++;
					sc = 1;
					len = 0;
					
				} else if (ch == sep.subcomponent) {
					sc++;
					len = 0;
					
				} else {
					len++;
				}
			}
		}
		
		if (found) {
			return indexes;
			
		} else {
			System.out.println(String.format("could not find indexes of %s, last pos is s=%d f=%d fr=%d c=%d sc=%d len=%d", pos, s, f, r, c, sc, len));
			return null;
		}
	}
	
	public static String printLocations (Message msg) throws Exception {
		StringMessageVisitor mv = new StringMessageVisitor();
		MessageVisitors.visit(msg, mv);
		return mv.toString();
	}
	
}
