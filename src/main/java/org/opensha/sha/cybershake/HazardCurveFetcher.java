package org.opensha.sha.cybershake;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.opensha.commons.data.function.ArbitrarilyDiscretizedFunc;
import org.opensha.commons.data.function.DiscretizedFunc;
import org.opensha.commons.data.siteData.SiteData;
import org.opensha.commons.data.siteData.impl.CVM4BasinDepth;
import org.opensha.commons.data.siteData.impl.WillsMap2006;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.calc.hazardMap.HazardDataSetLoader;
import org.opensha.sha.calc.mcer.AbstractMCErProbabilisticCalc;
import org.opensha.sha.cybershake.calc.mcer.CyberShakeMCErDeterministicCalc;
import org.opensha.sha.cybershake.db.CybershakeIM;
import org.opensha.sha.cybershake.db.CybershakeRun;
import org.opensha.sha.cybershake.db.CybershakeSite;
import org.opensha.sha.cybershake.db.CybershakeSiteInfo2DB;
import org.opensha.sha.cybershake.db.Cybershake_OpenSHA_DBApplication;
import org.opensha.sha.cybershake.db.DBAccess;
import org.opensha.sha.cybershake.db.HazardCurve2DB;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class HazardCurveFetcher {
	
	DBAccess db;
	HazardCurve2DB curve2db;
	HazardCurve2DB curvePointsDB;
	CybershakeSiteInfo2DB site2db;
	
	List<Integer> curveIDs;
	List<CybershakeSite> sites;
	List<Integer> runIDs;
	List<DiscretizedFunc> funcs;
	
	List<CybershakeSite> allSites = null;
	
	private CybershakeIM im;
	
	public HazardCurveFetcher(DBAccess db, int datasetID, int imTypeID) {
		this(db, new int[] {datasetID}, imTypeID);
	}
	
	public HazardCurveFetcher(DBAccess db, int[] datasetIDs, int imTypeID) {
		this.initDBConnections(db);
		Preconditions.checkArgument(datasetIDs.length > 0);
		ArrayList<Integer> curveIDs = new ArrayList<>();
		for (int datasetID : datasetIDs)
			curveIDs.addAll(curve2db.getAllHazardCurveIDsForDataset(datasetID, imTypeID));
		init(curveIDs, imTypeID);
	}
	
	public HazardCurveFetcher(DBAccess db, int erfID, int rupVarScenarioID, int sgtVarID, int velModelID, int imTypeID) {
		this.initDBConnections(db);
		System.out.println("rupV: " + rupVarScenarioID + " sgtV: " + sgtVarID + " velID: " + velModelID);
		init(curve2db.getAllHazardCurveIDs(erfID, rupVarScenarioID, sgtVarID, velModelID, imTypeID), imTypeID);
	}
	
	public HazardCurveFetcher(DBAccess db, List<CybershakeRun> runs, int[] datasetIDs, int imTypeID) {
		this(db, runs, datasetIDs, imTypeID, null);
	}
	
	public HazardCurveFetcher(DBAccess db, List<CybershakeRun> runs, int[] datasetIDs, int imTypeID, DBAccess customCurvesDB) {
		this.initDBConnections(db, customCurvesDB);
		List<Integer> curveIDs = new ArrayList<>();
		for (int datasetID : datasetIDs) {
			for (CybershakeRun run : runs) {
				ArrayList<Integer> curvesForRun = curve2db.getAllHazardCurveIDsForRun(run.getRunID(), datasetID, imTypeID);
				if (curvesForRun == null || curvesForRun.isEmpty())
					System.err.println("WARNING: no curves for runID="+run.getRunID()+", datasetID="+datasetID+", imTypeID="+imTypeID);
				else
					curveIDs.addAll(curvesForRun);
			}
		}
		init(curveIDs, imTypeID);
	}
	
	private void init(List<Integer> curveIDs, int imTypeID) {
		this.curveIDs = curveIDs;
		this.im = curve2db.getIMFromID(imTypeID);
		sites = new ArrayList<CybershakeSite>();
		funcs = new ArrayList<DiscretizedFunc>();
		runIDs = new ArrayList<Integer>();
		// keep track of duplicates - we want the most recent curve (which will be the first in the list
		// as the accessor sorts by curve date desc
		HashSet<Integer> siteIDs = new HashSet<Integer>();
		List<Integer> duplicateCurveIDs = Lists.newArrayList();
		System.out.println("Start loop...");
		for (int i=0; i<curveIDs.size(); i++) {
			int id = curveIDs.get(i);
			int siteID = curve2db.getSiteIDFromCurveID(id);
			if (siteIDs.contains(siteID)) {
//				System.out.println("Removing duplicate for site "+siteID+". Deleting curve ID "+id);
				duplicateCurveIDs.add(id);
				continue;
			} else {
				siteIDs.add(siteID);
			}
			sites.add(site2db.getSiteFromDB(siteID));
			DiscretizedFunc curve = curvePointsDB.getHazardCurve(id);
			Preconditions.checkNotNull(curve, "Curve is null? Curve ID=%s, site ID=%s", id, siteID);
			funcs.add(curve);
			runIDs.add(curve2db.getRunIDForCurve(id));
		}
		for (int id : duplicateCurveIDs)
			// use indexof because remove(int) will do index not object
			curveIDs.remove(curveIDs.indexOf(id));
	}
	
	public void scaleForDuration(double origDuration, double newDuration) {
		for (int f=0; f<funcs.size(); f++) {
			DiscretizedFunc curve = funcs.get(f);
			ArbitrarilyDiscretizedFunc modCurve = new ArbitrarilyDiscretizedFunc();
			for (int i=0; i<curve.size(); i++) {
				double x = curve.getX(i);
				double origY = curve.getY(i);
				double rate = -Math.log(1 - origY)/origDuration;
				double modY = 1d - Math.exp(-rate*newDuration);
				modCurve.set(x, modY);
			}
			funcs.set(f, modCurve);
		}
	}
	
	public List<DiscretizedFunc> getCurvesForRun(int runID) {
		List<DiscretizedFunc> curves = new ArrayList<>();
		for (int i=0; i<runIDs.size(); i++)
			if (runIDs.get(i) == runID)
				curves.add(funcs.get(i));
		return curves;
	}
	
	public List<DiscretizedFunc> getCurvesForSite(int siteID) {
		List<DiscretizedFunc> curves = new ArrayList<>();
		for (int i=0; i<runIDs.size(); i++)
			if (sites.get(i).id == siteID)
				curves.add(funcs.get(i));
		return curves;
	}
	
	private void initDBConnections(DBAccess db) {
		initDBConnections(db, null);
	}
	
	private void initDBConnections(DBAccess db, DBAccess customCurvesDB) {
		this.db = db;
		curve2db = new HazardCurve2DB(db);
		if (customCurvesDB == null)
			curvePointsDB = curve2db;
		else
			curvePointsDB = new HazardCurve2DB(customCurvesDB);
		site2db = new CybershakeSiteInfo2DB(db);
	}
	
	public DBAccess getDBAccess() {
		return db;
	}
	
	public ArrayList<Double> getSiteValues(boolean isProbAt_IML, double val) {
		ArrayList<Double> vals = new ArrayList<Double>();
		for (DiscretizedFunc func : funcs) {
			if (val < 0)
				// RTGM
				vals.add(AbstractMCErProbabilisticCalc.calcRTGM(func));
			else
				vals.add(HazardDataSetLoader.getCurveVal(func, isProbAt_IML, val));
		}
		return vals;
	}
	
	public List<Double> calcRTGM() {
		List<Double> vals = new ArrayList<Double>();
		for (DiscretizedFunc func : funcs)
			vals.add(AbstractMCErProbabilisticCalc.calcRTGM(func));
		return vals;
	}
	
	public List<Double> calcDeterministic(CyberShakeMCErDeterministicCalc detCalc) {
		List<Double> vals = new ArrayList<Double>();
		for (int runID : runIDs) {
			try {
				vals.add(detCalc.calc(runID, im).getVal());
				System.out.println("Finished Det Calc "+vals.size()+"/"+runIDs.size());
			} catch (SQLException e) {
				ExceptionUtils.throwAsRuntimeException(e);
			}
		}
		return vals;
	}

	public List<Integer> getCurveIDs() {
		return curveIDs;
	}
	
	public List<Integer> getRunIDs() {
		return runIDs;
	}

	public List<CybershakeSite> getCurveSites() {
		return sites;
	}

	public List<DiscretizedFunc> getFuncs() {
		return funcs;
	}
	
	public List<CybershakeSite> getAllSites() {
		if (allSites == null) {
			allSites = site2db.getAllSitesFromDB();
		}
		return allSites;
	}
	
	public CybershakeIM getIM() {
		return im;
	}
	
	public void writeCurveToFile(DiscretizedFunc curve, String fileName) throws IOException {
		FileWriter fw = new FileWriter(fileName);
		
		for (int i = 0; i < curve.size(); ++i)
			fw.write(curve.getX(i) + " " + curve.getY(i) + "\n");
		
		fw.close();
	}
	
	public void saveAllCurvesToDir(String outDir) {
		File outDirFile = new File(outDir);
		
		if (!outDirFile.exists())
			outDirFile.mkdir();
		
		if (!outDir.endsWith(File.separator))
			outDir += File.separator;
		
		List<DiscretizedFunc> curves = this.getFuncs();
		List<CybershakeSite> curveSites = this.getCurveSites();
		
		for (int i=0; i<curves.size(); i++) {
			DiscretizedFunc curve = curves.get(i);
			CybershakeSite site = curveSites.get(i);
			
			String fileName = outDir + site.short_name + "_" + site.lat + "_" + site.lon + ".txt";
			
			System.out.println("Writing " + fileName);
			
			try {
				this.writeCurveToFile(curve, fileName);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public static void main(String args[]) throws IOException {
		String outDir = "/home/kevin/CyberShake/curve_data";
		
		DBAccess db = Cybershake_OpenSHA_DBApplication.getDB();
		
		System.out.println("1");
		
		int erfID = 35;
		HazardCurveFetcher fetcher = new HazardCurveFetcher(db, erfID, 3, 5, 1, 21);
		
		System.out.println("2");
		
//		fetcher.saveAllCurvesToDir(outDir);
		
		WillsMap2006 wills = new WillsMap2006();
		CVM4BasinDepth cvm = new CVM4BasinDepth(SiteData.TYPE_DEPTH_TO_2_5);
		
		List<CybershakeSite> sites = fetcher.getCurveSites();
		String tot = "";
		for (CybershakeSite site : sites) {
			String str = site.lon + ", " + site.lat + ", " + site.short_name + ", ";
			if (site.type_id == CybershakeSite.TYPE_POI)
				str += "Point of Interest";
			else if (site.type_id == CybershakeSite.TYPE_BROADBAND_STATION)
				str += "Seismic Station";
			else if (site.type_id == CybershakeSite.TYPE_PRECARIOUS_ROCK)
				str += "Precarious Rock";
			else if (site.type_id == CybershakeSite.TYPE_TEST_SITE)
				continue;
			else
				str += "Unknown";
			double vs30 = wills.getValue(site.createLocation());
			double basin = cvm.getValue(site.createLocation());
			str += ", " + vs30 + ", " + basin;
			System.out.println(str);
			tot += str + "\n";
		}
		System.out.print(tot);
		
		System.exit(0);
	}
}
