/*
 * (c) Copyright Christian P. Fries, Germany. All rights reserved. Contact: email@christian-fries.de.
 * 
 * Created on 15.07.2012
 */

package net.finmath.timeseries.models.parametric;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.SimplePointChecker;
import org.apache.commons.math3.random.MersenneTwister;

import net.finmath.optimizer.LevenbergMarquardt;
import net.finmath.optimizer.SolverException;
import net.finmath.timeseries.HistoricalSimulationModel;
import net.finmath.timeseries.TimeSeriesInterface;
import net.finmath.timeseries.TimeSeriesModelParametric;
import net.finmath.timeseries.TimeSeriesView;

/**
 * Lognormal process with ARMAGARCH(1,1) volatility.
 * 
 * This class estimates the process
 * \[
 *   \mathrm{d} \log(X) = \sigma(t) \mathrm{d}W(t)
 * \]
 * where \( \sigma \) is given by a ARMAGARCH(1,1) process.
 * 
 * @author Christian Fries
 */
public class ARMAGARCH implements TimeSeriesModelParametric, HistoricalSimulationModel {

	private TimeSeriesInterface timeSeries;	

	private int maxIterations = 10000000;

	/*
	 * Model properties
	 */
	private final String[] parameterNames	= new String[] { "omega", "alpha", "beta", "theta", "mu", "phi" };
	private final double[] parameterGuess	= new double[] { 0.10, 0.3, 0.3, 0.0, 0.0, 0.0 };
	private final double[] parameterStep	= new double[] { 0.001, 0.001, 0.001, 0.001, 0.0001, 0.001 }; 
	private final double[] lowerBound;
	private final double[] upperBound;

	public ARMAGARCH(TimeSeriesInterface timeSeries) {
		this.timeSeries = timeSeries;

		lowerBound = new double[] { 0, 							0, 0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
		upperBound = new double[] { Double.POSITIVE_INFINITY,	1, 1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
	}

	/**
	 * @param parameters Given model parameters.
	 * @return The log likelihood for the given model parameters.
	 */
	public double getLogLikelihoodForParameters(double[] parameters)
	{
		double omega		= parameters[0];
		double alpha		= parameters[1];
		double beta			= parameters[2];
		double theta		= parameters[3];
		double mu			= parameters[4];
		double phi			= parameters[5];

		double logLikelihood = 0.0;

		double volScaling	= 1;
		double evalPrev		= 0.0;
		double eval			= volScaling * (Math.log((timeSeries.getValue(1))/(timeSeries.getValue(0))));
		if(Double.isInfinite(eval) || Double.isNaN(eval)) eval = 0;
		double h			= omega / (1.0 - alpha - beta);
		double m			= 0.0; // xxx how to init?

		logLikelihood += - Math.log(h) - 2 * Math.log((Math.abs(timeSeries.getValue(1)))/volScaling) - eval*eval / h;

		int length = timeSeries.getNumberOfTimePoints();
		for (int i = 1; i < length-1; i++) {
			m = -mu -theta * m + eval - phi * evalPrev;
			h = (omega + alpha * m * m) + beta * h;

			double value1 = timeSeries.getValue(i);
			double value2 = timeSeries.getValue(i+1);

			double evalNext	= volScaling * (Math.log((value2)/(value1)));
			if(Double.isInfinite(evalNext) || Double.isNaN(evalNext)) evalNext = 0;
			double mNext = -mu - theta * m + evalNext - phi * eval;

			// We need to take abs here, which corresponds to the assumption that -x is lognormal, given that we encounter a negative values.
			logLikelihood += - Math.log(h) - 2 * Math.log((Math.abs(value2))/volScaling) -  mNext* mNext / h;

			evalPrev = eval;
			eval = evalNext;
		}
		logLikelihood += - Math.log(2 * Math.PI) * (double)(length-1);
		logLikelihood *= 0.5;

		return logLikelihood;
	}

	public double getLastResidualForParameters(double[] parameters) {
		double omega		= parameters[0];
		double alpha		= parameters[1];
		double beta			= parameters[2];
		double theta		= parameters[3];
		double mu			= parameters[4];
		double phi			= parameters[5];

		double evalPrev		= 0.0;
		double volScaling	= 1;
		double h			= omega / (1.0 - alpha - beta);
		double m			= 0.0; // xxx how to init?

		int length = timeSeries.getNumberOfTimePoints();
		for (int i = 1; i < length-1; i++) {
			double eval	= volScaling * (Math.log((timeSeries.getValue(i))/(timeSeries.getValue(i-1))));
			if(Double.isInfinite(eval) || Double.isNaN(eval)) eval = 0;

			m = -mu -theta * m + eval - phi * evalPrev;
			h = (omega + alpha * m * m) + beta * h;

			evalPrev = eval;
		}

		return h;
	}

	public double[] getSzenarios(double[] parameters) {
		double omega		= parameters[0];
		double alpha		= parameters[1];
		double beta			= parameters[2];
		double theta		= parameters[3];
		double mu			= parameters[4];
		double phi			= parameters[5];

		ArrayList<Double> szenarios = new ArrayList<Double>();

		double volScaling	= 1;
		double evalPrev		= 0.0;
		double h			= omega / (1.0 - alpha - beta);
		double m			= 0.0;
		double vol = Math.sqrt(h) / volScaling;
		for (int i = 1; i <= timeSeries.getNumberOfTimePoints()-1; i++) {
			double y = Math.log((timeSeries.getValue(i))/(timeSeries.getValue(i-1)));

			if(Double.isInfinite(y) || Double.isNaN(y)) y = 0;

			// y = sqrt(h) * eps + sqrt(h_prev) epsprev + mu yprev
			// h = omega + alpha y^2 + beta h
			double eval	= volScaling * y;
			m = -mu -theta * m + eval - phi * evalPrev;

			double value = (m / volScaling) / vol;
			szenarios.add(value);

			h = (omega + alpha * m * m) + beta * h;
			vol = Math.sqrt(h) / volScaling;

			evalPrev = eval;
		}
		Collections.sort(szenarios);

		// Get szenarios on current vol
		double[] szenariosArray = new double[szenarios.size()];
		for(int i=0; i<szenarios.size(); i++) szenariosArray[i] = szenarios.get(i) * vol;

		return szenariosArray;
	}

	public double[] getQuantilPredictionsForParameters(double[] parameters, double[] quantiles) {
		double[] szenarios = getSzenarios(parameters);

		double[] quantileValues = new double[quantiles.length];
		for(int i=0; i<quantiles.length; i++) {
			double quantile = quantiles[i];
			double quantileIndex = (szenarios.length+1) * quantile - 1;
			int quantileIndexLo = (int)quantileIndex;
			int quantileIndexHi = quantileIndexLo+1;

			double szenarioRelativeChange;
			if(szenarios.length > 0) {
				szenarioRelativeChange = Math.exp(
						(
								(quantileIndexHi-quantileIndex) * szenarios[Math.max(quantileIndexLo,0               )]
										+ (quantileIndex-quantileIndexLo) * szenarios[Math.min(quantileIndexHi,szenarios.length-1)]
								));
			}
			else {
				szenarioRelativeChange = 1.0;
			}

			double quantileValue = (timeSeries.getValue(timeSeries.getNumberOfTimePoints()-1)) * szenarioRelativeChange;

			quantileValues[i] = quantileValue;
		}

		return quantileValues;
	}


	/* (non-Javadoc)
	 * @see net.finmath.timeseries.HistoricalSimulationModel#getBestParameters()
	 */
	@Override
	public Map<String, Object> getBestParameters() {
		return getBestParameters(null);
	}

	/* (non-Javadoc)
	 * @see net.finmath.timeseries.HistoricalSimulationModel#getBestParameters(java.util.Map)
	 */
	@Override
	public Map<String, Object> getBestParameters(Map<String, Object> guess) {

		// Create the objective function for the solver
		class GARCHMaxLikelihoodFunction implements MultivariateFunction, Serializable {

			private static final long serialVersionUID = 7072187082052755854L;

			public double value(double[] variables) {

				double omega	= variables[0];
				double alpha	= variables[1];
				double beta		= variables[2];
				double theta	= variables[3];
				double mu			= variables[4];
				double phi			= variables[5];

				double logLikelihood = getLogLikelihoodForParameters(variables);

				// Penalty to prevent solver from hitting the bounds
				logLikelihood -= Math.max(1E-30-omega,0)/1E-30;
				logLikelihood -= Math.max(1E-30-alpha,0)/1E-30;
				logLikelihood -= Math.max((alpha-1)+1E-30,0)/1E-30;
				logLikelihood -= Math.max(1E-30-beta,0)/1E-30;
				logLikelihood -= Math.max((beta-1)+1E-30,0)/1E-30;

				return logLikelihood;
			}

		}
		final GARCHMaxLikelihoodFunction objectiveFunction = new GARCHMaxLikelihoodFunction();

		// Create a guess for the solver
		final double[] guessParameters = new double[parameterGuess.length];
		System.arraycopy(parameterGuess, 0, guessParameters, 0, parameterGuess.length);

		if(guess != null) {
			// A guess was provided, use that one
			guessParameters[0]	= (Double)guess.get("Omega");
			guessParameters[1]	= (Double)guess.get("Alpha");
			guessParameters[2]	= (Double)guess.get("Beta");
			guessParameters[3]	= (Double)guess.get("Theta");
			guessParameters[4]	= (Double)guess.get("Mu");
			guessParameters[5]	= (Double)guess.get("Phi");
		}


		// Seek optimal parameter configuration
		LevenbergMarquardt lm = new LevenbergMarquardt(guessParameters, new double[] { 1000.0 }, 100*maxIterations, 2) {
			private static final long serialVersionUID = -8844232820888815090L;

			@Override
			public void setValues(double[] parameters, double[] values) throws SolverException {
				values[0] = objectiveFunction.value(parameters);
			}
		};

		double[] bestParameters = null;

		boolean isUseLM = false;

		if(isUseLM) {
			try {
				lm.run();
			} catch (SolverException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			bestParameters = lm.getBestFitParameters();
		}
		else {
			org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer optimizer2 = new org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer(maxIterations, Double.POSITIVE_INFINITY, true, 0, 0, new MersenneTwister(3141), false, new SimplePointChecker<org.apache.commons.math3.optim.PointValuePair>(0, 0))
			{
				@Override
				public double computeObjectiveValue(double[] params) {
					return objectiveFunction.value(params);
				}

				/* (non-Javadoc)
				 * @see org.apache.commons.math3.optim.nonlinear.scalar.MultivariateOptimizer#getGoalType()
				 */
				@Override
				public org.apache.commons.math3.optim.nonlinear.scalar.GoalType getGoalType() {
					// TODO Auto-generated method stub
					return org.apache.commons.math3.optim.nonlinear.scalar.GoalType.MAXIMIZE;
				}

				/* (non-Javadoc)
				 * @see org.apache.commons.math3.optim.BaseMultivariateOptimizer#getStartPoint()
				 */
				@Override
				public double[] getStartPoint() {
					return guessParameters;
				}

				/* (non-Javadoc)
				 * @see org.apache.commons.math3.optim.BaseMultivariateOptimizer#getLowerBound()
				 */
				@Override
				public double[] getLowerBound() {
					return lowerBound;
				}

				/* (non-Javadoc)
				 * @see org.apache.commons.math3.optim.BaseMultivariateOptimizer#getUpperBound()
				 */
				@Override
				public double[] getUpperBound() {
					return upperBound;
				}
			};

			try {
				org.apache.commons.math3.optim.PointValuePair result = optimizer2.optimize(
						new org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer.PopulationSize((int) (4 + 3 * Math.log((double)guessParameters.length))),
						new org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer.Sigma(parameterStep)
						);
				bestParameters = result.getPoint();
			} catch(org.apache.commons.math3.exception.MathIllegalStateException e) {
				System.out.println("Solver failed");
				bestParameters = guessParameters;
			}
		}

		// Transform parameters to GARCH parameters
		double omega		= bestParameters[0];
		double alpha		= bestParameters[1];
		double beta			= bestParameters[2];
		double theta		= bestParameters[3];
		double mu			= bestParameters[4];
		double phi			= bestParameters[5];

		double[] quantiles		= {0.005, 0.01, 0.02, 0.05, 0.5};
		double[] quantileValues	= getQuantilPredictionsForParameters(bestParameters, quantiles);

		Map<String, Object> results = new HashMap<String, Object>();
		results.put("parameters", bestParameters);
		results.put("Omega", omega);
		results.put("Alpha", alpha);
		results.put("Beta", beta);
		results.put("Theta", theta);
		results.put("Mu", mu);
		results.put("Phi", phi);
		results.put("Szenarios", this.getSzenarios(bestParameters));
		results.put("Likelihood", this.getLogLikelihoodForParameters(bestParameters));
		results.put("Vol", Math.sqrt(this.getLastResidualForParameters(bestParameters)));
		results.put("Quantile=05%", quantileValues[0]);
		results.put("Quantile=1%", quantileValues[1]);
		results.put("Quantile=2%", quantileValues[2]);
		results.put("Quantile=5%", quantileValues[3]);
		results.put("Quantile=50%", quantileValues[4]);
		return results;
	}

	private static double restrictToOpenSet(double value, double lowerBond, double upperBound) {
		value = Math.max(value, lowerBond  * (1.0+Math.signum(lowerBond)*1E-15) + 1E-15);
		value = Math.min(value, upperBound * (1.0-Math.signum(upperBound)*1E-15) - 1E-15);
		return value;
	}

	@Override
	public TimeSeriesModelParametric getCloneCalibrated(TimeSeriesInterface timeSeries) {
		return new ARMAGARCH(timeSeries);
	}

	@Override
	public HistoricalSimulationModel getCloneWithWindow(int windowIndexStart, int windowIndexEnd) {
		return new ARMAGARCH(new TimeSeriesView(timeSeries, windowIndexStart, windowIndexEnd));
	}

	@Override
	public double[] getParameters() {
		return (double[])getBestParameters().get("parameters");
	}

	@Override
	public String[] getParameterNames() {
		return parameterNames;
	}
}
