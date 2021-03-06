package hu.mv;

import hu.*;

import java.util.*;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.Location;
import ca.uhn.hl7v2.model.*;
import ca.uhn.hl7v2.validation.*;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * visit every primitive and validate the data type
 */
public class ValidatingMessageVisitor extends MessageVisitorAdapter {
	
	private final ValidationContext vc = ValidationContextFactory.defaultValidation();
	private final String version;
	private final String msgCr;
	private final MsgSep sep;
	private final List<Comment> comments = new ArrayList<>();
	private final String selectedValue;
	
	private int segOrd;
	
	/**
	 * msgCr - reference for getting index of errors
	 */
	public ValidatingMessageVisitor (String msgCr, MsgSep sep, String version, String selectedValue) {
		this.msgCr = msgCr;
		this.sep = sep;
		this.version = version;
		this.selectedValue = selectedValue;
	}
	
	@Override
	public boolean start2 (AbstractSegment segment, Location location) throws HL7Exception {
		segOrd++;
		// TODO
		//currentGroup.isRepeating()
		
		String msg = null;
		Comment.Type type = null;
		if (((AbstractGroup)segment.getParent()).getNonStandardNames().contains(segment.getName())) {
			msg = "Non standard segment";
			type = Comment.Type.ERROR;
		} else {
			type = Comment.Type.SEGMENT;
		}
		
		MsgPos pos = new MsgPos(segOrd, 0, 0, 1, 1);
		int[] index = MsgUtil.getIndexes(msgCr, sep, pos);
		comments.add(new Comment(pos, msg, index, type));
		return true;
	}
	
	@Override
	public boolean start2 (AbstractGroup group, Location location) throws HL7Exception {
		return true;
	}
	
	@Override
	public boolean start2 (Field field, Location location) throws HL7Exception {
		field.isEmpty();
		// TODO
//		segment.numFields();
//		currentSegment.isRequired(location.getField());
//		currentSegment.getMaxCardinality(location.getField());
//		currentSegment.getLength(location.getField());
		return true;
	}
	
	@Override
	public boolean visit2 (final Primitive primitive, final Location location) throws HL7Exception {
		Comment.Type vmType = null;
		String vmMsg = "";
		
		if (selectedValue != null && selectedValue.equalsIgnoreCase(primitive.getValue())) {
			vmType = Comment.Type.VALUE;
			
		} else {
			final Collection<PrimitiveTypeRule> rules = vc.getPrimitiveRules(version, primitive.getName(), primitive);
			for (PrimitiveTypeRule rule : rules) {
				final String v = rule.correct(primitive.getValue());
				final ValidationException[] ves = rule.apply(v);
				if (ves.length > 0) {
					vmType = Comment.Type.ERROR;
					vmMsg = rule.getDescription().replace("%s", primitive.getValue());
					break;
				}
			}
		}
		
		if (vmType != null) {
			System.out.println("vmv visit2: " + location + " -> " + vmMsg);
			final int rep = Math.max(location.getFieldRepetition(), 0);
			final int comp = Math.max(location.getComponent(), 1);
			final int subcomp = Math.max(location.getSubcomponent(), 1);
			final int field = location.getField();
			final MsgPos pos = new MsgPos(segOrd, field, rep, comp, subcomp);
			final int[] index = MsgUtil.getIndexes(msgCr, sep, pos);
			comments.add(new Comment(pos, vmMsg, index, vmType));
		}
		
		return true;
	}
	
	/** get the list of errors, maybe empty, never null */
	public List<Comment> getComments () {
		return comments;
	}
}
