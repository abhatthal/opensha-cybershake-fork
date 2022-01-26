package org.opensha.sha.cybershake.db;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

import org.opensha.commons.data.Site;
import org.opensha.commons.geo.Location;
import org.opensha.commons.geo.LocationList;
import org.opensha.commons.geo.LocationUtils;
import org.opensha.commons.geo.Region;
import org.opensha.sha.earthquake.ERF;
import org.opensha.sha.earthquake.ProbEqkRupture;
import org.opensha.sha.earthquake.ProbEqkSource;
import org.opensha.sha.faultSurface.EvenlyGridCenteredSurface;
import org.opensha.sha.faultSurface.AbstractEvenlyGriddedSurface;
import org.opensha.sha.faultSurface.EvenlyGriddedSurface;
import org.opensha.sha.faultSurface.InterpolatedEvenlyGriddedSurface;
import org.opensha.sha.faultSurface.PointSurface;
import org.opensha.sha.faultSurface.RuptureSurface;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;

import scratch.kevin.simulators.erf.RSQSimSectBundledERF.RSQSimProbEqkRup;

public class CybershakeSiteInfo2DB {


	public static double CUT_OFF_DISTANCE = 200;
	public static boolean FORCE_CUTOFF = false; // if true, will force to use new cutoff distance
	private SiteInfo2DB site2db;
	private ERF2DBAPI erf2db = null;
	private BufferedWriter out = null;
	private boolean logging = false;
	private boolean forceAddRuptures = false;

	private boolean matchSourceNames = false;
	HashMap<Integer, Integer> sourceMap = null;

	public CybershakeSiteInfo2DB(DBAccess db){
		site2db = new SiteInfo2DB(db);
		erf2db = new ERF2DB(db);
	}

	public void setMatchSourceNames(boolean match) {
		matchSourceNames = match;
	}



	/**
	 * Puts the Cybershake locations in the database
	 * @param siteName
	 * @param siteShortName
	 * @param siteLat
	 * @param siteLon
	 * @return the SiteId from the database
	 */
	public int  putCybershakeLocationInDB(CybershakeSite site){
		return site2db.insertSite(site);
	}

	/**
	 * Returns the CyberShake site id for the corresponding Cybershake short site name in the database
	 * @param cybershakeShortSiteName
	 * @return
	 */
	public int getCybershakeSiteId(String cybershakeShortSiteName){
		return site2db.getSiteId(cybershakeShortSiteName);
	}

	/**
	 * Returns the Cybershake site id for the corresponding location with given Lat and Lon.
	 * @param lat
	 * @param lon
	 * @return
	 */
	public int getCybershakeSiteId(double lat,double lon){
		return site2db.getSiteId(lat, lon);
	}


	/**
	 * Finds all the ruptures that have any location on their surface within Cybershake location
	 * circular regional bounds.
	 * @param erf
	 * @param erfId
	 * @param siteId
	 * @param locLat
	 * @param locLon
	 */
	public ArrayList<int[]> putCyberShakeLocationSrcRupInfo(ERF eqkRupForecast,int erfId,
			int siteId,double locLat,double locLon) {
		return putCyberShakeLocationSrcRupInfo(eqkRupForecast, erfId, 
				siteId, locLat, locLon, false);
	}

	/**
	 * Finds all the ruptures that have any location on their surface within Cybershake location
	 * circular regional bounds with option to add ruptures that are not already in database.
	 * @param erf
	 * @param erfId
	 * @param siteId
	 * @param locLat
	 * @param locLon
	 * @param checkAddRup make sure rupture is in DB, and if not, add it
	 */
	public ArrayList<int[]> putCyberShakeLocationSrcRupInfo(
			ERF eqkRupForecast, int erfId, int siteId,
			double locLat, double locLon, boolean checkAddRup) {
		return putCyberShakeLocationSrcRupInfo(eqkRupForecast, erfId, 
				siteId, locLat, locLon, checkAddRup, "");
	}

	private HashMap<Integer, Integer> getSourceMatchMap(ERF eqkRupForecast, int erfID) {
		if (sourceMap == null) {
			sourceMap = new HashMap<Integer, Integer>();

			for (int sourceID=0; sourceID<eqkRupForecast.getNumSources(); sourceID++) {
				String name = eqkRupForecast.getSource(sourceID).getName();

				int csID = this.erf2db.getSourceIDFromName(erfID, name);

				if (csID >= 0) {
					sourceMap.put(sourceID, csID);
					System.out.println("Matched source " + sourceID + " with DB source " + csID);
				} else {
					//					System.out.println("Source " + name + " not in DB!");
				}
			}
		}

		return sourceMap;
	}

	private int getCSSourceID(ERF eqkRupForecast, int erfID, int erfSourceID) {
		int csSource = erfSourceID;

		if (matchSourceNames) {
			csSource = getMatchedCSSourceID(eqkRupForecast, erfID, erfSourceID);
			System.out.print("Matching sourceID " + erfSourceID + "...");
			if (csSource < 0) {
				System.out.println("it's not in there!");
			}
		}

		return csSource;
	}

	public int getMatchedCSSourceID(ERF eqkRupForecast, int erfID, int erfSourceID) {
		HashMap<Integer, Integer> map = this.getSourceMatchMap(eqkRupForecast, erfID);
		if (map.containsKey(erfSourceID)) {
			return map.get(erfSourceID);
		} else {
			return -1;
		}
	}

	/**
	 * Finds all the ruptures that have any location on their surface within Cybershake location
	 * circular regional bounds with option to add ruptures that are not already in database (with logging).
	 * @param erf
	 * @param erfId
	 * @param siteId
	 * @param locLat
	 * @param locLon
	 * @param checkAddRup make sure rupture is in DB, and if not, add it
	 * @param addLogFileName filename to log to (no logging if empty)
	 */
	public ArrayList<int[]> putCyberShakeLocationSrcRupInfo(
			ERF eqkRupForecast, int erfId, int siteId,
			double locLat, double locLon, boolean checkAddRup, String addLogFileName) {
		Location loc = new Location(locLat, locLon);
		double cutoffDist = site2db.getSiteCutoffDistance(siteId);
		//		Region region = new Region(loc, site2db.getSiteCutoffDistance(siteId));
		int numSources = eqkRupForecast.getNumSources();

		ArrayList<int[]> newRups = new ArrayList<int[]>();

		Site site = new Site(loc);

		// Going over each and every source in the forecast
		int count = 0;
		for (int sourceIndex = 0; sourceIndex < numSources; ++sourceIndex) {

			if (sourceIndex < this.skipSource)
				continue;
			this.skipSource = -1;

			int csSource = getCSSourceID(eqkRupForecast, erfId, sourceIndex);

			// get the ith source
			ProbEqkSource source = eqkRupForecast.getSource(sourceIndex);
			int numRuptures = source.getNumRuptures();

			double sourceDist = source.getMinDistance(site);
			if (sourceDist > cutoffDist)
				continue;

			ArrayList<Integer> rupsToAdd = new ArrayList<Integer>();
			ArrayList<Double> rupDistsToAdd = new ArrayList<Double>();

			// going over all the ruptures in the source
			for (int rupIndex = 0; rupIndex < numRuptures; ++rupIndex) {

				if (rupIndex < this.skipRup)
					continue;
				this.skipRup = -1;

				ProbEqkRupture rupture = source.getRupture(rupIndex);
				RuptureSurface rupSurface = rupture.getRuptureSurface();
				if (rupSurface instanceof InterpolatedEvenlyGriddedSurface)
					rupSurface = ((InterpolatedEvenlyGriddedSurface)rupSurface).getLowResSurface();

				count++;

				//check if the rupture is there
				if (checkAddRup || this.forceAddRuptures) {
					boolean log = addLogFileName != null && addLogFileName.length() > 0;
					//check if it's a dup
					if (this.site2db.isSiteRupInDB(erfId, sourceIndex, rupIndex, siteId)) {
						System.out.println("It's a duplicate...skipping!");
						break;
					}
					if (this.forceAddRuptures || !this.site2db.isRupInDB(erfId, sourceIndex, rupIndex)) { //it's not in the database
						if (this.forceAddRuptures) {
							if (count % 100 == 0) {
								System.out.println("Adding rupture " + count);
							}
						} else
							System.out.println("Rupture " + sourceIndex + " " + rupIndex + " not in DB, adding...");
						//log it
						if (log) {
							int newRupToAdd[] = {sourceIndex, rupIndex};
							newRups.add(newRupToAdd);
							try {
								if (out == null) {
									out = new BufferedWriter(new FileWriter(addLogFileName));
									logging = true;
								}
								out.append(sourceIndex + " " + rupIndex + "\n");
								out.flush();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
						//add it
						erf2db.insertSrcRupInDB(eqkRupForecast, erfId, sourceIndex, rupIndex);
						//flush log in case something bad happens
						if (addLogFileName.length() > 0) {
							try {
								out.flush();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}
				//				System.out.println("Inserting Rupture " + sourceIndex + ", " + rupIndex + " for site " + siteId);
				rupsToAdd.add(rupIndex);
				rupDistsToAdd.add(rupSurface.getDistanceRup(loc));
			}

			// add the list
			if (rupsToAdd.size() > 0) {
				System.out.println("Inserting " + rupsToAdd.size() + " ruptures for Site=" + siteId + " and source=" + sourceIndex);

				this.site2db.insertSite_RuptureInfoList(siteId, erfId, csSource, rupsToAdd, rupDistsToAdd, site2db.getSiteCutoffDistance(siteId));
			}

		}
		return newRups;
	}

	private int skipSource = -1;

	public void setSkipToSource(int source) {
		this.skipSource = source;
	}

	private int skipRup = -1;

	public void setSkipToRup(int rup) {
		this.skipRup = rup;
	}

	public void setForceAddRuptures(boolean force) {
		this.forceAddRuptures = force;
	}

	public void closeWriter() {
		if (logging) {
			try {
				out.close();
				logging = false;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Computes the regional bounds for the given cybershake and puts in the
	 * database. Also put the ruptureId and sourceId from ERF that dictates the
	 * min/max lat/lon for the region.
	 * 
	 * @param eqkRupForecast
	 * @param erfId
	 * @param siteId
	 * @param locLat
	 * @param locLon
	 * @param update - update bounds, don't reinsert
	 */
	public void putCyberShakeLocationRegionalBounds(ERF eqkRupForecast,int erfId, int siteId,
			double locLat,double locLon, boolean update){

		Location loc = new Location(locLat,locLon);
		Site site = new Site(loc);
		double distCutoff = site2db.getSiteCutoffDistance(siteId);

		int numSources = eqkRupForecast.getNumSources();

		double minLat = Double.POSITIVE_INFINITY;
		int minLatRupId = -1,minLatSrcId= -1;
		double maxLat = Double.NEGATIVE_INFINITY;
		int maxLatRupId =-1,maxLatSrcId=-1;
		double minLon = Double.POSITIVE_INFINITY;
		int minLonRupId =-1,minLonSrcId = -1;
		double maxLon = Double.NEGATIVE_INFINITY;
		int maxLonRupId = -1, maxLonSrcId =-1;

		//Going over each and every source in the forecast
		for (int sourceIndex = 0; sourceIndex < numSources; ++sourceIndex) {
			// get the ith source
			ProbEqkSource source = eqkRupForecast.getSource(sourceIndex);
			int numRuptures = source.getNumRuptures();
			
			if (source.getMinDistance(site) > distCutoff)
				continue;

			int csSource = getCSSourceID(eqkRupForecast, erfId, sourceIndex);

			//going over all the ruptures in the source
			for (int rupIndex = 0; rupIndex < numRuptures; ++rupIndex) {
				ProbEqkRupture rupture = source.getRupture(rupIndex);
				
				if (rupture instanceof RSQSimProbEqkRup) {
					RSQSimProbEqkRup rsRup = (RSQSimProbEqkRup)rupture;
					Range<Double> latRange = rsRup.getElemLatRange();
					Range<Double> lonRange = rsRup.getElemLonRange();
//					System.out.println("RSQSIM. latRange: "+latRange+"\tlonRange: "+lonRange);
					if (latRange.lowerEndpoint() < minLat){
						minLat = latRange.lowerEndpoint();
						minLatRupId = rupIndex;
						minLatSrcId = csSource;
					}
					if (latRange.upperEndpoint() > maxLat){
						maxLat = latRange.upperEndpoint();
						maxLatRupId = rupIndex;
						maxLatSrcId = csSource;
					}
					if (lonRange.lowerEndpoint() < minLon){
						minLon = lonRange.lowerEndpoint();
						minLonRupId = rupIndex;
						minLonSrcId = csSource;
					}
					if (lonRange.upperEndpoint() > maxLon){
						maxLon = lonRange.upperEndpoint();
						maxLonRupId = rupIndex;
						maxLonSrcId = csSource;
					}
				} else if (rupture.getRuptureSurface() instanceof PointSurface) {
					Location ptLoc = ((PointSurface)rupture.getRuptureSurface()).getLocation();
					double lat = ptLoc.getLatitude();
					double lon = ptLoc.getLongitude();
					if (lat < minLat){
						minLat = lat;
						minLatRupId = rupIndex;
						minLatSrcId = csSource;
					}
					if (lat > maxLat){
						maxLat = lat;
						maxLatRupId = rupIndex;
						maxLatSrcId = csSource;
					}
					if (lon < minLon){
						minLon = lon;
						minLonRupId = rupIndex;
						minLonSrcId = csSource;
					}
					if (lon > maxLon){
						maxLon = lon;
						maxLonRupId = rupIndex;
						maxLonSrcId = csSource;
					}
				} else {
					Preconditions.checkState(rupture.getRuptureSurface() instanceof EvenlyGriddedSurface);
					EvenlyGriddedSurface rupSurface = (EvenlyGriddedSurface)rupture.getRuptureSurface();
					if (rupSurface instanceof InterpolatedEvenlyGriddedSurface)
						rupSurface = ((InterpolatedEvenlyGriddedSurface)rupSurface).getLowResSurface();

					//getting the iterator for all points on the rupture
					ListIterator<Location> it = rupSurface.getAllByRowsIterator();
					it = rupSurface.getAllByRowsIterator();
					while (it.hasNext()) {
						Location ptLoc = it.next();
						double lat = ptLoc.getLatitude();
						double lon = ptLoc.getLongitude();
						if (lat < minLat){
							minLat = lat;
							minLatRupId = rupIndex;
							minLatSrcId = csSource;
						}
						if (lat > maxLat){
							maxLat = lat;
							maxLatRupId = rupIndex;
							maxLatSrcId = csSource;
						}
						if (lon < minLon){
							minLon = lon;
							minLonRupId = rupIndex;
							minLonSrcId = csSource;
						}
						if (lon > maxLon){
							maxLon = lon;
							maxLonRupId = rupIndex;
							maxLonSrcId = csSource;
						}
					}
				}
			}
		}
		long start = 0;
		if (Cybershake_OpenSHA_DBApplication.timer) {
			start = System.currentTimeMillis();
		}
		if (update) {
			site2db.updateSiteRegionalBounds(siteId, erfId,site2db.getSiteCutoffDistance(siteId),
					maxLat, maxLatSrcId, maxLatRupId, minLat,
					minLatSrcId, minLatRupId, maxLon, maxLonSrcId, 
					maxLonRupId, minLon, minLonSrcId, minLonRupId);
		} else {
			site2db.insertSiteRegionalBounds(siteId, erfId,site2db.getSiteCutoffDistance(siteId),
					maxLat, maxLatSrcId, maxLatRupId, minLat,
					minLatSrcId, minLatRupId, maxLon, maxLonSrcId, 
					maxLonRupId, minLon, minLonSrcId, minLonRupId);
		}
		if (Cybershake_OpenSHA_DBApplication.timer) {
			long total = (System.currentTimeMillis() - start);
			System.out.println("Took " + total + " miliseconds to insert regional bounds!");
		}
	}



	/**
	 * 
	 * @return the list of cybershake sites
	 */
	public List<String> getCS_SitesList(){
		return site2db.getAllSites();
	}


	/**
	 * 
	 * @return the list of all Cybershake Site Locations
	 */
	public LocationList getCS_SitesListLocations(){
		return site2db.getAllSitesLocation();
	}

	/**
	 * 
	 * @param siteShortName short site name as in database for Cybershake site
	 * @return the Earthquake rupture forecast source id's for a given cybershake site.
	 */
	public List<Integer> getSrcIDsForSite(String csSiteName, int erfID){;
	return site2db.getSrcIdsForSite(csSiteName, erfID);
	}

	/**
	 * 
	 * @param siteShortName
	 * @param srcId
	 * @return the list of rupture ids 
	 */
	public List<Integer> getRupIDsForSite(String csSiteName, int erfID, int srcID){
		return site2db.getRupIdsForSite(csSiteName, erfID, srcID);
	}


	/**
	 * 
	 * @param csSiteName
	 * @return the Geographic locaton for the given Cybershake site
	 */
	public Location getCyberShakeSiteLocation(String csSiteName){
		return site2db.getLocationForSite(csSiteName);
	}

	/**
	 * Gets a CybershakeSite from the Database
	 * @param shortName
	 * @return
	 */
	public CybershakeSite getSiteFromDB(String shortName) {
		return site2db.getSiteFromDB(shortName);
	}

	/**
	 * Gets a CybershakeSite from the Database
	 * @param shortName
	 * @return
	 */
	public CybershakeSite getSiteFromDB(int siteID) {
		return site2db.getSiteFromDB(siteID);
	}

	/**
	 * Gets all CybershakeSite's from the Database
	 * @return
	 */
	public List<CybershakeSite> getAllSitesFromDB() {
		return site2db.getAllSitesFromDB();
	}

	public SiteInfo2DB getSitesDB() {
		return site2db;
	}

	public static void main(String args[]) {

		CybershakeSiteInfo2DB site2db = new CybershakeSiteInfo2DB(Cybershake_OpenSHA_DBApplication.getDB());

		ERF erf = MeanUCERF2_ToDB.createUCERF2ERF();
		erf.updateForecast();

		for (int sourceID=0; sourceID<erf.getNumSources(); sourceID++) {
			int id34 = site2db.getMatchedCSSourceID(erf, 34, sourceID);
			System.out.println("ERF35: " + sourceID + " => ERF34: " + id34);
		}
	}


}
