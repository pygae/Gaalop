package de.gaalop.maple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.gaalop.Notifications;
import de.gaalop.cfg.AssignmentNode;
import de.gaalop.cfg.BlockEndNode;
import de.gaalop.cfg.BreakNode;
import de.gaalop.cfg.ControlFlowGraph;
import de.gaalop.cfg.ControlFlowVisitor;
import de.gaalop.cfg.EmptyControlFlowVisitor;
import de.gaalop.cfg.EndNode;
import de.gaalop.cfg.ExpressionStatement;
import de.gaalop.cfg.IfThenElseNode;
import de.gaalop.cfg.LoopNode;
import de.gaalop.cfg.Macro;
import de.gaalop.cfg.Node;
import de.gaalop.cfg.SequentialNode;
import de.gaalop.cfg.StartNode;
import de.gaalop.cfg.StoreResultNode;
import de.gaalop.dfg.BaseVector;
import de.gaalop.dfg.BinaryOperation;
import de.gaalop.dfg.EmptyExpressionVisitor;
import de.gaalop.dfg.Equality;
import de.gaalop.dfg.Expression;
import de.gaalop.dfg.ExpressionFactory;
import de.gaalop.dfg.FloatConstant;
import de.gaalop.dfg.Inequality;
import de.gaalop.dfg.InnerProduct;
import de.gaalop.dfg.Multiplication;
import de.gaalop.dfg.MultivectorComponent;
import de.gaalop.dfg.OuterProduct;
import de.gaalop.dfg.Relation;
import de.gaalop.dfg.Subtraction;
import de.gaalop.dfg.Variable;
import de.gaalop.maple.engine.MapleEngine;
import de.gaalop.maple.engine.MapleEngineException;
import de.gaalop.maple.parser.MapleLexer;
import de.gaalop.maple.parser.MapleParser;
import de.gaalop.maple.parser.MapleTransformer;

/**
 * This visitor creates code for Maple.
 */
public class MapleCfgVisitor2 implements ControlFlowVisitor {

	private class CheckGAVisitor extends EmptyExpressionVisitor {

		private boolean isGA = false;

		@Override
		public void visit(BaseVector node) {
			isGA = true;
		}

		@Override
		public void visit(InnerProduct node) {
			isGA = true;
		}

		@Override
		public void visit(OuterProduct node) {
			isGA = true;
		}

		@Override
		public void visit(Relation node) {
			isGA = true;
		}

		@Override
		public void visit(Variable node) {
			if (gaVariables.contains(node)) {
				isGA = true;
			}
		}
	}

	/**
	 * This visitor re-orders compare operations like >, <= or == to a left-hand side expression that is compared to 0.
	 * 
	 * @author Christian Schwinn
	 * 
	 */
	private class ReorderConditionVisitor extends EmptyExpressionVisitor {

		private IfThenElseNode root;

		public ReorderConditionVisitor(IfThenElseNode root) {
			this.root = root;
		}

		private void reorderToLeft(BinaryOperation node) {
			Expression left = node.getLeft();
			Expression right = node.getRight();
			Subtraction lhs = ExpressionFactory.subtract(left.copy(), right.copy());
			Variable v = new Variable("condition_");
			Expression newLeft = v;
			Expression newRight = new FloatConstant(0);
			try {
				String assignment = generateCode(v) + ":=" + generateCode(lhs) + ";";
				engine.evaluate(assignment);
				String opt = simplify(v);
				List<AssignmentNode> newNodes = parseMapleCode(graph, opt);
				graph.addLocalVariable(v);
				boolean hasScalarPart = false;
				for (AssignmentNode newAssignment : newNodes) {
					root.insertBefore(newAssignment);
					if (!hasScalarPart) {
						if (newAssignment.getVariable() instanceof MultivectorComponent) {
							MultivectorComponent mc = (MultivectorComponent) newAssignment.getVariable();
							if (mc.getBladeIndex() == 0) {
								hasScalarPart = true;
								newLeft = mc;
							}
						}
					}
				}
				if (!hasScalarPart || newNodes.size() > 1) {
					throw new IllegalArgumentException("Condition in if-statement '" + root.getCondition()
							+ "' is not scalar and cannot be evaluated.");
				} else {
					root.insertBefore(new StoreResultNode(graph, v));
				}
			} catch (MapleEngineException e) {
				throw new RuntimeException("Unable to optimize condition " + lhs + " in Maple.", e);
			}

			node.replaceExpression(left, newLeft);
			node.replaceExpression(right, newRight);

			node.getLeft().accept(this);
		}

		@Override
		public void visit(Equality node) {
			reorderToLeft(node);
		}

		@Override
		public void visit(Inequality node) {
			reorderToLeft(node);
		}

		@Override
		public void visit(Relation node) {
			reorderToLeft(node);
		}

	}

	/**
	 * Simple helper visitor used to inline parts of conditional statements.
	 * 
	 * @author Christian Schwinn
	 * 
	 */
	private class InlineBlockVisitor extends EmptyControlFlowVisitor {

		private final IfThenElseNode root;
		private final Node branch;
		private final Node successor;

		/**
		 * Creates a new visitor with given root and branch.
		 * 
		 * @param root root node from which to inline a branch
		 * @param branch first node of branch to be inlined
		 */
		public InlineBlockVisitor(IfThenElseNode root, Node branch) {
			this.root = root;
			this.branch = branch;
			successor = root.getSuccessor();
		}

		private void replaceSuccessor(Node oldSuccessor, Node newSuccessor) {
			Set<Node> predecessors = new HashSet<Node>(oldSuccessor.getPredecessors());
			for (Node p : predecessors) {
				p.replaceSuccessor(oldSuccessor, newSuccessor);
			}
		}

		@Override
		public void visit(IfThenElseNode node) {
			// we peek only to next level of nested statements
			if (node == root) {
				if (node.getPositive() == branch) {
					if (!(branch instanceof BlockEndNode)) {
						replaceSuccessor(node, branch);
					}
					node.getPositive().accept(this);
				} else if (node.getNegative() == branch) {
					if (!(branch instanceof BlockEndNode)) {
						replaceSuccessor(node, branch);
					}
					node.getNegative().accept(this);
				}
				graph.removeNode(node);
			}
			node.getSuccessor().accept(this);
		}

		@Override
		public void visit(BlockEndNode node) {
			// this relies on the fact that nested statements are being ignored in visit(IfThenElseNode),
			// otherwise successor could be the wrong one
			if (node.getBase() == root) {
				replaceSuccessor(node, successor);
			}
		}

	}

	private Log log = LogFactory.getLog(MapleCfgVisitor2.class);

	private MapleEngine engine;

	private HashMap<String, String> oldMinVal;
	private HashMap<String, String> oldMaxVal;

	private Plugin plugin;

	/** Used to distinguish normal assignments and such from a loop or if-statement where GA must be eliminated. */
	private int blockDepth = 0;
	private Set<Variable> counterVariables = new HashSet<Variable>();
	private SequentialNode currentRoot;

	private Map<Variable, Set<MultivectorComponent>> initializedVariables = new HashMap<Variable, Set<MultivectorComponent>>();
	private Set<Variable> gaVariables = new HashSet<Variable>();

	private ControlFlowGraph graph;

	public MapleCfgVisitor2(MapleEngine engine, Plugin plugin) {
		this.engine = engine;
		this.plugin = plugin;
	}

	@Override
	public void visit(StartNode startNode) {
		graph = startNode.getGraph();
		plugin.notifyStart();
		startNode.getSuccessor().accept(this);
	}

	@Override
	public void visit(AssignmentNode node) {
		Variable variable = node.getVariable();
		Expression value = node.getValue();
		Node successor = node.getSuccessor();

		// check if GA is contained in value
		CheckGAVisitor gaVisitor = new CheckGAVisitor();
		value.accept(gaVisitor);
		boolean isGA = gaVisitor.isGA;
		if (isGA) {
			gaVariables.add(variable);
		}

		if (counterVariables.contains(variable)) {
			if (isGA) {
				throw new IllegalArgumentException("Counter variable " + variable
						+ " of loop is not allowed to use Geometric Algebra");
			}
			// do not process this assignment
			successor.accept(this);
			return;
		}

		if (blockDepth > 0) {
			// optimize variable and add variables for coefficients in front of block
			initializeCoefficients(node.getVariable());
			// optimize value in a temporary variable and add missing initializations
			initializeMissingCoefficients(node);
			// reset Maple binding with linear combination of variables for coefficients
			resetVariable(variable);
		}

		assignVariable(variable, value);

		if (blockDepth > 0) {
			assignCoefficients(node, variable);
		}

		// notify observers about progress (must be called before successor.accept(this))
		plugin.notifyProgress();
		graph.removeNode(node);
		successor.accept(this);
	}

	private void initializeCoefficients(Variable variable) {
		List<AssignmentNode> coefficients = optimizeVariable(graph, variable);
		for (AssignmentNode coefficient : coefficients) {
			if (coefficient.getVariable() instanceof MultivectorComponent) {
				MultivectorComponent component = (MultivectorComponent) coefficient.getVariable();
				Variable tempVar = new Variable(getTempVarName(component));
				AssignmentNode initialization = new AssignmentNode(graph, tempVar, coefficient.getValue());
				currentRoot.insertBefore(initialization);
				if (initializedVariables.get(variable) == null) {
					initializedVariables.put(variable, new HashSet<MultivectorComponent>());
				}
				initializedVariables.get(variable).add(component);
			}
		}
	}

	private void initializeMissingCoefficients(AssignmentNode node) {
		Variable temp = new Variable("__temp__");
		assignVariable(temp, node.getValue());
		List<MultivectorComponent> coefficients = getComponents(optimizeVariable(graph, temp));
		for (MultivectorComponent coefficient : coefficients) {
			Variable origialVariable = node.getVariable();
			String optVarName = origialVariable.getName() + "_opt";
			MultivectorComponent originalComp = new MultivectorComponent(optVarName, coefficient.getBladeIndex());
			Variable tempVar = new Variable(getTempVarName(originalComp));
			Set<MultivectorComponent> initCoefficients = initializedVariables.get(origialVariable);
			if (!(initCoefficients != null && initCoefficients.contains(originalComp))) {
				AssignmentNode initialization = new AssignmentNode(graph, tempVar, new FloatConstant(0));
				currentRoot.insertBefore(initialization);
				initCoefficients.add(originalComp);
			}
		}
	}

	private void assignCoefficients(AssignmentNode base, Variable variable) {
		List<AssignmentNode> coefficients = optimizeVariable(graph, variable);
		for (AssignmentNode coefficient : coefficients) {
			if (coefficient.getVariable() instanceof MultivectorComponent) {
				MultivectorComponent mc = (MultivectorComponent) coefficient.getVariable();
				Variable newVariable = new Variable(getTempVarName(mc));
				Expression newValue = coefficient.getValue();
				if (!newVariable.equals(newValue)) {
					AssignmentNode newAssignment = new AssignmentNode(graph, newVariable, newValue);
					base.insertAfter(newAssignment);
				}
			}
		}
		resetVariable(variable);
	}

	private void resetVariable(Variable variable) {
		Set<MultivectorComponent> components = initializedVariables.get(variable);
		if (components == null || components.size() == 0) {
			throw new IllegalStateException("No components to reset for variable " + variable);
		}
		Expression[] products = new Expression[components.size()];
		int i = 0;
		for (MultivectorComponent mc : components) {
			Variable coefficient = new Variable(getTempVarName(mc));
			Expression blade = graph.getBladeList()[mc.getBladeIndex()];
			Multiplication product = ExpressionFactory.product(coefficient, blade);
			products[i++] = product;
		}
		Expression sum;
		if (products.length > 1) {
			sum = ExpressionFactory.sum(products);
		} else {
			sum = products[0];
		}

		assignVariable(variable, sum);
	}

	/**
	 * Extracts the {@link MultivectorComponent}s from a list of coefficients.
	 * 
	 * @param coefficients nodes from the Maple parser
	 * @return list of multivector components
	 */
	private List<MultivectorComponent> getComponents(List<AssignmentNode> coefficients) {
		List<MultivectorComponent> components = new ArrayList<MultivectorComponent>();
		for (AssignmentNode coefficient : coefficients) {
			if (coefficient.getVariable() instanceof MultivectorComponent) {
				components.add((MultivectorComponent) coefficient.getVariable());
			}
		}
		return components;
	}

	/**
	 * Translates the given variable and value to Maple syntax and executes it.
	 * 
	 * @param variable
	 * @param value
	 */
	private void assignVariable(Variable variable, Expression value) {
		String variableCode = generateCode(variable);
		StringBuilder codeBuffer = new StringBuilder();
		codeBuffer.append(variableCode);
		codeBuffer.append(" := ");
		codeBuffer.append(generateCode(value));
		codeBuffer.append(";\n");

		try {
			engine.evaluate(codeBuffer.toString());
		} catch (MapleEngineException e) {
			throw new RuntimeException("Unable to process assignment " + variable + " := " + value + " in Maple.", e);
		}
	}

	private String generateCode(Expression expression) {
		MapleDfgVisitor visitor = new MapleDfgVisitor();
		expression.accept(visitor);
		return visitor.getCode();
	}

	@Override
	public void visit(ExpressionStatement node) {
		String command = generateCode(node.getExpression());
		command += ";\n";
		try {
			engine.evaluate(command);
		} catch (MapleEngineException e) {
			throw new RuntimeException("Unable to simplify statement " + node + " in Maple.", e);
		}
		graph.removeNode(node);
		node.getSuccessor().accept(this);
	}

	private String getTempVarName(MultivectorComponent component) {
		return component.getName() + "__" + component.getBladeIndex();
	}

	@Override
	public void visit(StoreResultNode node) {
		List<AssignmentNode> newNodes = optimizeVariable(graph, node.getValue());
		for (SequentialNode newNode : newNodes) {
			node.insertBefore(newNode);
		}
		node.getSuccessor().accept(this);
	}

	/**
	 * Simplifies the given variable and parses the Maple code to return the new nodes.
	 */
	private List<AssignmentNode> optimizeVariable(ControlFlowGraph graph, Variable v) {
		String simplification = simplify(v);
		log.debug("Maple simplification of " + v + ": " + simplification);
		return parseMapleCode(graph, simplification);
	}

	/**
	 * Simplifies a single Expression node.
	 * 
	 * @param expression The data flow graph that should be simplified
	 * @return The code Maple returned as the simplification.
	 */
	private String simplify(Expression expression) {
		StringBuilder codeBuffer = new StringBuilder();
		codeBuffer.append("gaalop(");
		codeBuffer.append(generateCode(expression));
		codeBuffer.append(");\n");

		try {
			return engine.evaluate(codeBuffer.toString());
		} catch (MapleEngineException e) {
			throw new RuntimeException("Unable to apply gaalop() function on expression " + expression + " in Maple.",
					e);
		}
	}

	/**
	 * Parses a snippet of maple code and returns a list of CFG nodes that implement the returned maple expressions.
	 * 
	 * @param graph The control flow graph the new nodes should be created in.
	 * @param mapleCode The code returned by Maple.
	 * @return A list of control flow nodes modeling the returned code.
	 */
	private List<AssignmentNode> parseMapleCode(ControlFlowGraph graph, String mapleCode) {
		oldMinVal = new HashMap<String, String>();
		oldMaxVal = new HashMap<String, String>();

		/* fill the Maps with the min and maxvalues from the nodes */
		for (Variable v : graph.getInputVariables()) {
			if (v.getMinValue() != null)
				oldMinVal.put(v.getName(), v.getMinValue());
			if (v.getMaxValue() != null)
				oldMaxVal.put(v.getName(), v.getMaxValue());
		}

		MapleLexer lexer = new MapleLexer(new ANTLRStringStream(mapleCode));
		MapleParser parser = new MapleParser(new CommonTokenStream(lexer));
		try {
			MapleParser.program_return result = parser.program();
			MapleTransformer transformer = new MapleTransformer(new CommonTreeNodeStream(result.getTree()));
			return transformer.script(graph, oldMinVal, oldMaxVal);
		} catch (RecognitionException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void visit(IfThenElseNode node) {
		if (blockDepth == 0) {
			currentRoot = node;
		}
		Expression condition = node.getCondition();
		try {
			boolean unknown = false;
			UsedVariablesVisitor visitor = new UsedVariablesVisitor();
			condition.accept(visitor);
			for (Variable v : visitor.getVariables()) {
				String name = v.getName();
				String result = engine.evaluate(name + ";");
				if (result.equals(name + "\n")) {
					unknown = true;
					break;
				}
			}
			if (!unknown) {
				StringBuilder codeBuffer = new StringBuilder();
				codeBuffer.append("evalb(");
				codeBuffer.append(generateCode(condition));
				codeBuffer.append(");\n");
				// try to evaluate the condition
				String result = engine.evaluate(codeBuffer.toString());
				log.debug("Maple simplification of IF condition " + condition + ": " + result);
				// if condition can be determined to be true or false, inline relevant part
				if ("true\n".equals(result)) {
					node.accept(new InlineBlockVisitor(node, node.getPositive()));
					node.getPositive().accept(this);
				} else if ("false\n".equals(result)) {
					node.accept(new InlineBlockVisitor(node, node.getNegative()));
					if (node.getNegative() instanceof BlockEndNode) {
						node.getSuccessor().accept(this);
					} else {
						node.getNegative().accept(this);
					}
				} else {
					// reset unknown status in order to process branches
					Notifications.addWarning("Could not evaluate condition " + condition);
					unknown = true;
				}
			}
			if (unknown) {
				ReorderConditionVisitor reorder = new ReorderConditionVisitor(node);
				condition.accept(reorder);

				blockDepth++;
				node.getPositive().accept(this);
				node.getNegative().accept(this);
				blockDepth--;

				if (blockDepth == 0) {
					initializedVariables.clear();
					counterVariables.clear();
				}
				node.getSuccessor().accept(this);
			}
		} catch (MapleEngineException e) {
			throw new RuntimeException("Unable to check condition " + condition + " in if-statement " + node, e);
		}
	}

	@Override
	public void visit(LoopNode node) {
		if (blockDepth == 0) {
			currentRoot = node;
		}

		Variable counter = node.getCounter();
		if (counter != null) {
			try {
				String query = counter + ";";
				String result = engine.evaluate(query);
				// FIXME: does not work for counter variables within another loop
				FloatConstant value = new FloatConstant(Float.parseFloat(result));
				AssignmentNode initCounter = new AssignmentNode(graph, counter, value);
				node.insertBefore(initCounter);
				graph.removeLocalVariable(counter);
				counterVariables.add(counter);
				String command = counter + ":='" + counter + "';";
				engine.evaluate(command);
			} catch (MapleEngineException e) {
				throw new RuntimeException("Could not reset counter variable " + counter);
			} catch (NumberFormatException e) {
				throw new RuntimeException("Counter variable " + counter + " is not scalar.", e);
			}
		}

		blockDepth++;
		node.getBody().accept(this);
		blockDepth--;

		if (blockDepth == 0) {
			initializedVariables.clear();
			counterVariables.clear();
		}
		node.getSuccessor().accept(this);
	}

	@Override
	public void visit(BreakNode node) {
		// nothing to do
	}

	@Override
	public void visit(BlockEndNode node) {
		// nothing to do
	}

	@Override
	public void visit(EndNode endNode) {
	}

	@Override
	public void visit(Macro node) {
		throw new IllegalStateException("Macros should have been inlined.");
	}
}
