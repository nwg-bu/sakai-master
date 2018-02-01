/**
 * Copyright (c) 2003-2017 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.gradebookng.business;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.gradebookng.business.exception.GbAccessDeniedException;
import org.sakaiproject.gradebookng.business.exception.GbException;
import org.sakaiproject.gradebookng.business.model.GbCourseGrade;
import org.sakaiproject.gradebookng.business.model.GbGradeCell;
import org.sakaiproject.gradebookng.business.model.GbGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbGradeLog;
import org.sakaiproject.gradebookng.business.model.GbGroup;
import org.sakaiproject.gradebookng.business.model.GbStudentGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbStudentNameSortOrder;
import org.sakaiproject.gradebookng.business.model.GbUser;
import org.sakaiproject.gradebookng.business.util.CourseGradeFormatter;
import org.sakaiproject.gradebookng.business.util.FormatHelper;
import org.sakaiproject.gradebookng.business.util.GbStopWatch;
import org.sakaiproject.gradebookng.tool.model.GradebookUiSettings;
import org.sakaiproject.service.gradebook.shared.AssessmentNotFoundException;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CategoryDefinition;
import org.sakaiproject.service.gradebook.shared.CommentDefinition;
import org.sakaiproject.service.gradebook.shared.CourseGrade;
import org.sakaiproject.service.gradebook.shared.GradeDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookExternalAssessmentService;
import org.sakaiproject.service.gradebook.shared.GradebookFrameworkService;
import org.sakaiproject.service.gradebook.shared.GradebookInformation;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookPermissionService;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.GraderPermission;
import org.sakaiproject.service.gradebook.shared.GradingType;
import org.sakaiproject.service.gradebook.shared.InvalidGradeException;
import org.sakaiproject.service.gradebook.shared.PermissionDefinition;
import org.sakaiproject.service.gradebook.shared.SortType;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.tool.gradebook.GradingEvent;
import org.sakaiproject.user.api.CandidateDetailProvider;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.FormattedText;
import org.sakaiproject.util.ResourceLoader;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Business service for GradebookNG
 *
 * This is not designed to be consumed outside of the application or supplied entityproviders. Use at your own risk.
 *
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */

// TODO add permission checks! Remove logic from entityprovider if there is a
// double up
// TODO some of these methods pass in empty lists and its confusing. If we
// aren't doing paging, remove this.

@Slf4j
public class GradebookNgBusinessService {

	@Setter
	private SiteService siteService;

	@Setter
	private UserDirectoryService userDirectoryService;

	@Setter
	private ToolManager toolManager;

	@Setter
	private GradebookService gradebookService;

	@Setter
	private GradebookPermissionService gradebookPermissionService;

	@Setter
	private GradebookFrameworkService gradebookFrameworkService;

	@Setter
	private GradebookExternalAssessmentService gradebookExternalAssessmentService;

	@Setter
	private CourseManagementService courseManagementService;

	@Setter
	private SecurityService securityService;

	@Setter
	private ServerConfigurationService serverConfigurationService;

	public static final String ASSIGNMENT_ORDER_PROP = "gbng_assignment_order";
	public static final String ICON_SAKAI = "icon-sakai--";

	public static final ResourceLoader externalAppLoader = new ResourceLoader("org.sakaiproject.localization.bundle.tool.tools");

	/**
	 * Get a list of all users in the current site that can have grades
	 *
	 * @return a list of users as uuids or null if none
	 */
	public List<String> getGradeableUsers() {
		return this.getGradeableUsers(getCurrentSiteId());
	}

	/**
	 * Get a list of all users in the given site that can have grades
	 *
	 * @return a list of users as uuids or null if none
	 */
	public List<String> getGradeableUsers(final String siteId) {
		return this.getGradeableUsers(siteId, null);
	}

	/**
	 * Get the list of gradeable users
	 * @param groupFilter
	 * @return
	 *
	 */
	public List<String> getGradeableUsers(final GbGroup groupFilter) {
		return this.getGradeableUsers(null, groupFilter);
	}

	/**
	 * Get a list of all users in the given site, filtered by the given group, that can have grades
	 *
	 * @param siteId the id of the site to lookup
	 * @param groupFilter GbGroupType to filter on
	 *
	 * @return a list of users as uuids or null if none
	 */
	public List<String> getGradeableUsers(final String siteId, final GbGroup groupFilter) {

		try {

			String givenSiteId = siteId;
			if (StringUtils.isBlank(givenSiteId)) {
				givenSiteId = getCurrentSiteId();
			}

			// note that this list MUST exclude TAs as it is checked in the
			// GradebookService and will throw a SecurityException if invalid
			// users are provided
			final Set<String> userUuids = this.siteService.getSite(givenSiteId).getUsersIsAllowed(GbRole.STUDENT.getValue());

			// filter the allowed list based on membership
			if (groupFilter != null && groupFilter.getType() != GbGroup.Type.ALL) {

				final Set<String> groupMembers = new HashSet<>();

				if (groupFilter.getType() == GbGroup.Type.GROUP) {
					final Set<Member> members = this.siteService.getSite(givenSiteId).getGroup(groupFilter.getId())
							.getMembers();
					for (final Member m : members) {
						if (userUuids.contains(m.getUserId())) {
							groupMembers.add(m.getUserId());
						}
					}
				}

				// only keep the ones we identified in the group
				userUuids.retainAll(groupMembers);
			}

			final GbRole role = this.getUserRole(givenSiteId);

			// if TA, pass it through the gradebook permissions (only if there
			// are permissions)
			if (role == GbRole.TA) {
				final User user = getCurrentUser();

				// if there are permissions, pass it through them
				// don't need to test TA access if no permissions
				final List<PermissionDefinition> perms = getPermissionsForUser(user.getId());
				if (!perms.isEmpty()) {

					final Gradebook gradebook = this.getGradebook(givenSiteId);

					// get list of sections and groups this TA has access to
					final List courseSections = this.gradebookService.getViewableSections(gradebook.getUid());

					// get viewable students.
					final List<String> viewableStudents = this.gradebookPermissionService.getViewableStudentsForUser(
							gradebook.getUid(), user.getId(), new ArrayList<>(userUuids), courseSections);

					if (viewableStudents != null) {
						userUuids.retainAll(viewableStudents); // retain only
																// those that
																// are visible
																// to this TA
					} else {
						userUuids.clear(); // TA can't view anyone
					}
				}
			}

			return new ArrayList<>(userUuids);

		} catch (final IdUnusedException e) {
			log.warn("IdUnusedException trying to getGradeableUsers", e);
			return null;
		} catch (final GbAccessDeniedException e) {
			log.warn("GbAccessDeniedException trying to getGradeableUsers", e);
			return null;
		}
	}

	/**
	 * Given a list of uuids, get a list of Users
	 *
	 * @param userUuids list of user uuids
	 * @return
	 */
	public List<User> getUsers(final List<String> userUuids) throws GbException {
		try {
			final List<User> users = this.userDirectoryService.getUsers(userUuids);
			Collections.sort(users, new LastNameComparator()); // default sort
			return users;
		} catch (final RuntimeException e) {
			// an LDAP exception can sometimes be thrown here, catch and rethrow
			throw new GbException("An error occurred getting the list of users.", e);
		}
	}

	/**
	* Create a map so that we can use the user's EID (from the imported file) to lookup their UUID (used to store the grade by the backend service).
	*
	* @return Map where the user's EID is the key and the {@link GbUser} object is the value
	*/
	public Map<String, GbUser> getUserEidMap() {
		final List<GbUser> users = getGbUsers(getGradeableUsers());
		final Map<String, GbUser> userEidMap = new HashMap<>();
		for (final GbUser user : users) {
			final String eid = user.getDisplayId();
			if (StringUtils.isNotBlank(eid)) {
				userEidMap.put(eid, user);
			}
		}

		return userEidMap;
	}

	/**
	 * Gets a List of GbUsers for the specified userUuids without any filtering.
	 * Appropriate only for back end business like grade exports, statistics, etc.
	 * @param userUuids
	 * @return
	 */
	public List<GbUser> getGbUsers(final List<String> userUuids)
	{
		final List<GbUser> gbUsers = new ArrayList<>(userUuids.size());
		final List<User> users = getUsers(userUuids);

		for (final User u : users) {
			gbUsers.add(new GbUser(u));
		}

		return gbUsers;
	}

	/**
	 * Helper to get a reference to the gradebook for the current site
	 *
	 * @return the gradebook for the site
	 */
	public Gradebook getGradebook() {
		return getGradebook(getCurrentSiteId());
	}

	/**
	 * Helper to get a reference to the gradebook for the specified site
	 *
	 * @param siteId the siteId
	 * @return the gradebook for the site
	 */
	private Gradebook getGradebook(final String siteId) {
		Gradebook gradebook = null;
		try {
			gradebook = (Gradebook) this.gradebookService.getGradebook(siteId);
		} catch (final GradebookNotFoundException e) {
			log.debug("Request made for inaccessible, adding gradebookUid={}", siteId);
			this.gradebookFrameworkService.addGradebook(siteId, siteId);
			try {
				gradebook = (Gradebook) this.gradebookService.getGradebook(siteId);
			} catch (final GradebookNotFoundException e2) {
				log.error("Request made and could not add inaccessible gradebookUid={}", siteId);
			}
		}
		return gradebook;
	}

	/**
	 * Get a list of assignments in the gradebook in the current site that the current user is allowed to access
	 *
	 * @return a list of assignments or empty list if none/no gradebook
	 */
	public List<Assignment> getGradebookAssignments() {
		return getGradebookAssignments(getCurrentSiteId(), SortType.SORT_BY_SORTING);
	}

	/**
	 * Get a list of assignments in the gradebook in the current site that the current user is allowed to access
	 *
	 * @return a list of assignments or empty list if none/no gradebook
	 */
	public List<Assignment> getGradebookAssignments(final String siteId) {
		return getGradebookAssignments(siteId, SortType.SORT_BY_SORTING);
	}

	/**
	 * Get a list of assignments in the gradebook in the current site that the current user is allowed to access sorted by the provided
	 * SortType
	 *
	 * @return a list of assignments or empty list if none/no gradebook
	 */
	public List<Assignment> getGradebookAssignments(final SortType sortBy) {
		return getGradebookAssignments(getCurrentSiteId(), sortBy);
	}

	/**
	 * Special operation to get a list of assignments in the gradebook that the specified student has access to. This taked into account
	 * externally defined assessments that may have grouping permissions applied.
	 *
	 * This should only be called if you are wanting to view the assignments that a student would see (ie if you ARE a student, or if you
	 * are an instructor using the student review mode)
	 *
	 * @return a list of assignments or empty list if none/no gradebook
	 */
	public List<Assignment> getGradebookAssignmentsForStudent(final String studentUuid) {

		final Gradebook gradebook = getGradebook(getCurrentSiteId());
		final List<Assignment> assignments = getGradebookAssignments();

		// NOTE: cannot do a role check here as it assumes the current user but this could have been called by an instructor (unless we add
		// a new method to handle this)
		// in any case the role check would just be a confirmation that the user passed in was a student.

		// for each assignment we need to check if it is grouped externally and if the user has access to the group
		final Iterator<Assignment> iter = assignments.iterator();
		while (iter.hasNext()) {
			final Assignment a = iter.next();
			if (a.isExternallyMaintained()) {
				if (this.gradebookExternalAssessmentService.isExternalAssignmentGrouped(gradebook.getUid(), a.getExternalId()) &&
						!this.gradebookExternalAssessmentService.isExternalAssignmentVisible(gradebook.getUid(), a.getExternalId(),
								studentUuid)) {
					iter.remove();
				}
			}
		}
		return assignments;
	}

	/**
	 * Get a list of assignments in the gradebook in the specified site that the current user is allowed to access, sorted by sort order
	 *
	 * @param siteId the siteId
	 * @return a list of assignments or empty list if none/no gradebook
	 */
	public List<Assignment> getGradebookAssignments(final String siteId, final SortType sortBy) {

		final List<Assignment> assignments = new ArrayList<>();
		final Gradebook gradebook = getGradebook(siteId);
		if (gradebook != null) {
			// applies permissions (both student and TA) and default sort is
			// SORT_BY_SORTING
			assignments.addAll(this.gradebookService.getViewableAssignmentsForCurrentUser(gradebook.getUid(), sortBy));
		}
		return assignments;
	}

	/**
	 * Get a list of categories in the gradebook in the current site
	 *
	 * @return list of categories or null if no gradebook
	 */
	public List<CategoryDefinition> getGradebookCategories() {
		return getGradebookCategories(getCurrentSiteId());
	}

	/**
	 * Get a list of categories in the gradebook in the specified site
	 *
	 * @param siteId the siteId
	 * @return a list of categories or empty if no gradebook
	 */
	public List<CategoryDefinition> getGradebookCategories(final String siteId) {
		final Gradebook gradebook = getGradebook(siteId);

		List<CategoryDefinition> rval = new ArrayList<>();

		if (gradebook == null) {
			return rval;
		}

		if (categoriesAreEnabled()) {
			rval = this.gradebookService.getCategoryDefinitions(gradebook.getUid());
		}

		GbRole role;
		try {
			role = this.getUserRole(siteId);
		} catch (final GbAccessDeniedException e) {
			log.warn("GbAccessDeniedException trying to getGradebookCategories", e);
			return rval;
		}

		// filter for TAs
		if (role == GbRole.TA) {
			final User user = getCurrentUser();

			// build a list of categoryIds
			final List<Long> allCategoryIds = new ArrayList<>();
			for (final CategoryDefinition cd : rval) {
				allCategoryIds.add(cd.getId());
			}

			if (allCategoryIds.isEmpty()) {
				return Collections.emptyList();
			}

			// get a list of category ids the user can actually view
			final List<Long> viewableCategoryIds = this.gradebookPermissionService
					.getCategoriesForUser(gradebook.getId(), user.getId(), allCategoryIds);

			// remove the ones that the user can't view
			final Iterator<CategoryDefinition> iter = rval.iterator();
			while (iter.hasNext()) {
				final CategoryDefinition categoryDefinition = iter.next();
				if (!viewableCategoryIds.contains(categoryDefinition.getId())) {
					iter.remove();
				}
			}

		}

		// Sort by categoryOrder
		Collections.sort(rval, CategoryDefinition.orderComparator);

		return rval;
	}

	/**
	 * Get a map of course grades for all students in the given site.
	 *
	 * @param siteId siteId to get course grades for
	 * @return the map of course grades for students, key = studentUuid, value = course grade, or an empty map
	 */
	public Map<String, CourseGrade> getCourseGrades(final String siteId) {
		final Gradebook gradebook = this.getGradebook(siteId);
		final List<String> studentUuids = this.getGradeableUsers(siteId);
		return this.getCourseGrades(gradebook, studentUuids, null);
	}

	/**
	 * Get a map of course grades for all students in the given site using the specified grading schema mapping.
	 *
	 * @param siteId siteId to get course grades for
	 * @param schema grading schema mapping
	 * @return the map of course grades for students, key = studentUuid, value = course grade, or an empty map
	 */
	public Map<String, CourseGrade> getCourseGrades(final String siteId, final Map<String,Double> schema) {
		final Gradebook gradebook = this.getGradebook(siteId);
		final List<String> studentUuids = this.getGradeableUsers(siteId);
		return this.getCourseGrades(gradebook, studentUuids, schema);
	}


	/**
	 * Get a map of course grades for the given users.
	 *
	 * @param studentUuids uuids for the students
	 * @return the map of course grades for students, key = studentUuid, value = course grade, or an empty map
	 */
	public Map<String, CourseGrade> getCourseGrades(final List<String> studentUuids) {
		final Gradebook gradebook = this.getGradebook();
		return this.getCourseGrades(gradebook, studentUuids, null);
	}

	/**
	 * Get a map of course grades for the given gradebook, users and optionally the grademap you want to use.
	 *
	 * @param gradebook the gradebook
	 * @param studentUuids uuids for the students
	 * @param gradeMap the grade mapping to use. This should be left blank if you are displaying grades to students so that the currently persisted value is used.
	 * @return the map of course grades for students, key = studentUuid, value = course grade, or an empty map
	 */
	private Map<String, CourseGrade> getCourseGrades(final Gradebook gradebook, final List<String> studentUuids, final Map<String, Double> gradeMap) {
		Map<String, CourseGrade> rval = new HashMap<>();
		if (gradebook != null) {
			if(gradeMap != null) {
				rval = this.gradebookService.getCourseGradeForStudents(gradebook.getUid(), studentUuids, gradeMap);
			} else {
				rval = this.gradebookService.getCourseGradeForStudents(gradebook.getUid(), studentUuids);
			}
		}
		return rval;
	}

	/**
	 * Get the course grade for a student. Safe to call when logged in as a student.
	 *
	 * @param studentUuid
	 * @return coursegrade. May have null fields if the coursegrade has not been released
	 */
	public CourseGrade getCourseGrade(final String studentUuid) {

		final Gradebook gradebook = this.getGradebook();
		final CourseGrade courseGrade = this.gradebookService.getCourseGradeForStudent(gradebook.getUid(), studentUuid);

		// handle the special case in the gradebook service where totalPointsPossible = -1
		if (courseGrade != null && (courseGrade.getTotalPointsPossible() == null || courseGrade.getTotalPointsPossible() == -1)) {
			courseGrade.setTotalPointsPossible(null);
			courseGrade.setPointsEarned(null);
		}

		return courseGrade;
	}

	/**
	 * Save the grade and comment for a student's assignment. Ignores the concurrency check.
	 *
	 * @param assignmentId id of the gradebook assignment
	 * @param studentUuid uuid of the user
	 * @param grade grade for the assignment/user
	 * @param comment optional comment for the grade. Can be null.
	 *
	 * @return
	 */
	public GradeSaveResponse saveGrade(final Long assignmentId, final String studentUuid, final String grade,
			final String comment) {

		final Gradebook gradebook = this.getGradebook();
		if (gradebook == null) {
			return GradeSaveResponse.ERROR;
		}

		return this.saveGrade(assignmentId, studentUuid, null, grade, comment);
	}

	/**
	 * Save the grade and comment for a student's assignment and do concurrency checking
	 *
	 * @param assignmentId id of the gradebook assignment
	 * @param studentUuid uuid of the user
	 * @param oldGrade old grade, passed in for concurrency checking/ If null, concurrency checking is skipped.
	 * @param newGrade new grade for the assignment/user
	 * @param comment optional comment for the grade. Can be null.
	 *
	 * @return
	 *
	 * 		TODO make the concurrency check a boolean instead of the null oldGrade
	 */
	public GradeSaveResponse saveGrade(final Long assignmentId, final String studentUuid, final String oldGrade,
			final String newGrade, final String comment) {

		final Gradebook gradebook = this.getGradebook();
		if (gradebook == null) {
			return GradeSaveResponse.ERROR;
		}

		// if newGrade is null, no change
		if (newGrade == null) {
			return GradeSaveResponse.NO_CHANGE;
		}

		// get current grade
		final String storedGrade = this.gradebookService.getAssignmentScoreString(gradebook.getUid(), assignmentId,
				studentUuid);

		// get assignment config
		final Assignment assignment = this.getAssignment(assignmentId);
		final Double maxPoints = assignment.getPoints();

		// check what grading mode we are in
		final GradingType gradingType = GradingType.valueOf(gradebook.getGrade_type());

		// if percentage entry type, reformat the grades, otherwise use points as is
		String newGradeAdjusted = newGrade;
		String oldGradeAdjusted = oldGrade;
		String storedGradeAdjusted = storedGrade;

		// Fix a problem when the grades comes from the old Gradebook API with locale separator, always compare the values using the same
		// separator
		if (StringUtils.isNotBlank(oldGradeAdjusted)) {
			oldGradeAdjusted = oldGradeAdjusted.replace(",".equals(FormattedText.getDecimalSeparator()) ? "." : ",",
					",".equals(FormattedText.getDecimalSeparator()) ? "," : ".");
		}
		if (StringUtils.isNotBlank(storedGradeAdjusted)) {
			storedGradeAdjusted = storedGradeAdjusted.replace(",".equals(FormattedText.getDecimalSeparator()) ? "." : ",",
					",".equals(FormattedText.getDecimalSeparator()) ? "," : ".");
		}

		if (gradingType == GradingType.PERCENTAGE) {
			// the passed in grades represents a percentage so the number needs to be adjusted back to points
			Double newGradePercentage = new Double("0.0");

			if(StringUtils.isNotBlank(newGrade)){
				newGradePercentage = FormatHelper.validateDouble(newGrade);
			}

			final Double newGradePointsFromPercentage = (newGradePercentage / 100) * maxPoints;
			newGradeAdjusted = FormatHelper.formatDoubleToDecimal(newGradePointsFromPercentage);

			// only convert if we had a previous value otherwise it will be out of sync
			if (StringUtils.isNotBlank(oldGradeAdjusted)) {
				// To check if our data is out of date, we first compare what we think
				// is the latest saved score against score stored in the database. As the score
				// is stored as points, we must convert this to a percentage. To be sure we're
				// comparing apples with apples, we first determine the number of decimal places
				// on the score, so the converted points-as-percentage is in the expected format.

				final Double oldGradePercentage = FormatHelper.validateDouble(oldGradeAdjusted);
				final Double oldGradePointsFromPercentage = (oldGradePercentage / 100) * maxPoints;

				oldGradeAdjusted = FormatHelper.formatDoubleToMatch(oldGradePointsFromPercentage, storedGradeAdjusted);

				oldGradeAdjusted = oldGradeAdjusted.replace(",".equals(FormattedText.getDecimalSeparator()) ? "." : ",",
					",".equals(FormattedText.getDecimalSeparator()) ? "," : ".");
			}

			// we dont need processing of the stored grade as the service does that when persisting.
		}

		// trim the .0 from the grades if present. UI removes it so lets standardise
		// trim to null so we can better compare against no previous grade being recorded (as it will be null)
		// Note that we also trim newGrade so that don't add the grade if the new grade is blank and there was no grade previously
		storedGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(storedGradeAdjusted, ".0"));
		oldGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(oldGradeAdjusted, ".0"));
		newGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(newGradeAdjusted, ".0"));

		storedGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(storedGradeAdjusted, ",0"));
		oldGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(oldGradeAdjusted, ",0"));
		newGradeAdjusted = StringUtils.trimToNull(StringUtils.removeEnd(newGradeAdjusted, ",0"));

		if (log.isDebugEnabled()) {
			log.debug("storedGradeAdjusted: " + storedGradeAdjusted);
			log.debug("oldGradeAdjusted: " + oldGradeAdjusted);
			log.debug("newGradeAdjusted: " + newGradeAdjusted);
		}

		// if comment longer than 500 chars, error.
		// the field is a CLOB, probably by mistake. Loading this field up may cause performance issues
		// see SAK-29595
		if (StringUtils.length(comment) > 500) {
			log.error("Comment too long. Maximum 500 characters.");
			return GradeSaveResponse.ERROR;
		}

		// no change
		if (StringUtils.equals(storedGradeAdjusted, newGradeAdjusted)) {
			final Double storedGradePoints = FormatHelper.validateDouble(storedGradeAdjusted);
			if (storedGradePoints != null && storedGradePoints.compareTo(maxPoints) > 0) {
				return GradeSaveResponse.OVER_LIMIT;
			} else {
				return GradeSaveResponse.NO_CHANGE;
			}
		}

		// concurrency check, if stored grade != old grade that was passed in,
		// someone else has edited.
		// if oldGrade == null, ignore concurrency check
		if (oldGrade != null && !StringUtils.equals(storedGradeAdjusted, oldGradeAdjusted)) {
			return GradeSaveResponse.CONCURRENT_EDIT;
		}

		GradeSaveResponse rval = null;

		if (StringUtils.isNotBlank(newGradeAdjusted)) {
			final Double newGradePoints = FormatHelper.validateDouble(newGradeAdjusted);

			// if over limit, still save but return the warning
			if (newGradePoints.compareTo(maxPoints) > 0) {
				log.debug("over limit. Max: {}", maxPoints);
				rval = GradeSaveResponse.OVER_LIMIT;
			}
		}

		// save
		try {
			// note, you must pass in the comment or it will be nulled out by the GB service
			// also, must pass in the raw grade as the service does conversions between percentage etc
			this.gradebookService.saveGradeAndCommentForStudent(gradebook.getUid(), assignmentId, studentUuid,
					newGrade, comment);
			if (rval == null) {
				// if we don't have some other warning, it was all OK
				rval = GradeSaveResponse.OK;
			}
		} catch (InvalidGradeException | GradebookNotFoundException | AssessmentNotFoundException e) {
			log.error("An error occurred saving the grade. {}: {}", e.getClass(), e.getMessage());
			rval = GradeSaveResponse.ERROR;
		}
		return rval;
	}

	/**
	 * Build the matrix of assignments, students and grades for all students
	 *
	 * @param assignments list of assignments
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(final List<Assignment> assignments) throws GbException {
		return this.buildGradeMatrix(assignments, this.getGradeableUsers());
	}

	/**
	 * Build the matrix of assignments and grades for the given users. In general this is just one, as we use it for the instructor view
	 * student summary but could be more for paging etc
	 *
	 * @param assignments list of assignments
	 * @param list of uuids
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(final List<Assignment> assignments,
			final List<String> studentUuids) throws GbException {
		return this.buildGradeMatrix(assignments, studentUuids, null);
	}

	/**
	 * Build the matrix of assignments, students and grades for all students, with the specified sortOrder
	 *
	 * @param assignments list of assignments
	 * @param uiSettings the UI settings. Wraps sort order and group filter (sort = null for no sort, filter = null for all groups)
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(final List<Assignment> assignments,
			final GradebookUiSettings uiSettings) throws GbException {
		return this.buildGradeMatrix(assignments, this.getGradeableUsers(uiSettings.getGroupFilter()), uiSettings);
	}

	/**
	 * Build the matrix of assignments and grades for the given users with the specified sort order
	 *
	 * @param assignments list of assignments
	 * @param studentUuids student uuids
	 * @param uiSettings the settings from the UI that wraps up preferences
	 * @return
	 *
	 * 		TODO refactor this into a hierarchical method structure
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(final List<Assignment> assignments,
			final List<String> studentUuids, final GradebookUiSettings uiSettings) throws GbException {

		// TODO move GradebookUISettings to business

		// settings could be null depending on constructor so it needs to be corrected
		final GradebookUiSettings settings = (uiSettings != null) ? uiSettings : new GradebookUiSettings();

		final GbStopWatch stopwatch = new GbStopWatch();
		stopwatch.start();
		stopwatch.timeWithContext("buildGradeMatrix", "buildGradeMatrix start", stopwatch.getTime());

		final Gradebook gradebook = this.getGradebook();
		if (gradebook == null) {
			return null;
		}
		stopwatch.timeWithContext("buildGradeMatrix", "getGradebook", stopwatch.getTime());

		final boolean categoriesEnabled = categoriesAreEnabled();
		stopwatch.timeWithContext("buildGradeMatrix", "categoriesAreEnabled", stopwatch.getTime());

		// get current user
		final String currentUserUuid = getCurrentUser().getId();

		// get role for current user
		GbRole role;
		try {
			role = this.getUserRole();
		} catch (final GbAccessDeniedException e) {
			throw new GbException("Error getting role for current user", e);
		}

		final Optional<Site> site = getCurrentSite();

		// get uuids as list of Users.
		// this gives us our base list and will be sorted as per our desired
		// sort method
		final List<User> students = getUsers(studentUuids);
		stopwatch.timeWithContext("buildGradeMatrix", "getUsers", stopwatch.getTime());
		if (settings.getStudentSortOrder() != null) {

			Comparator<User> comp = GbStudentNameSortOrder.FIRST_NAME == settings.getNameSortOrder() ? new FirstNameComparator()
					: new LastNameComparator();

			if (SortDirection.DESCENDING == settings.getStudentSortOrder()) {

				comp = Collections.reverseOrder(comp);
			}
			Collections.sort(students, comp);
		}
		else if (getCandidateDetailProvider() != null && settings.getStudentNumberSortOrder() != null)
		{
			if (site.isPresent())
			{
				Comparator<User> comp = new StudentNumberComparator(getCandidateDetailProvider(), site.get());
				if (SortDirection.DESCENDING.equals(settings.getStudentNumberSortOrder()))
				{
					comp = Collections.reverseOrder(comp);
				}
				Collections.sort(students, comp);
			}
		}
		stopwatch.timeWithContext("buildGradeMatrix", "sortUsers", stopwatch.getTime());

		// get course grades
		final Map<String, CourseGrade> courseGrades = getCourseGrades(studentUuids);

		stopwatch.timeWithContext("buildGradeMatrix", "getSiteCourseGrades", stopwatch.getTime());

		// setup a map because we progressively build this up by adding grades
		// to a student's entry
		final Map<String, GbStudentGradeInfo> matrix = new LinkedHashMap<>();

		// setup the course grade formatter
		// TODO we want the override except in certain cases. Can we hard code this?
		final CourseGradeFormatter courseGradeFormatter = new CourseGradeFormatter(
				gradebook,
				role,
				isCourseGradeVisible(currentUserUuid),
				settings.getShowPoints(),
				true);

		// seed the map for all students so we can progresseively add grades
		// also add the course grade here, to save an iteration later
		// TA permissions already included in course grade visibility
		for (final User student : students) {

			// create and add the user info
			final GbStudentGradeInfo sg = new GbStudentGradeInfo(student, getStudentNumber(student, site.orElse(null)));

			// add the course grade, including the display
			final CourseGrade courseGrade = courseGrades.get(student.getId());
			final GbCourseGrade gbCourseGrade = new GbCourseGrade(courseGrades.get(student.getId()));
			gbCourseGrade.setDisplayString(courseGradeFormatter.format(courseGrade));
			sg.setCourseGrade(gbCourseGrade);

			// add to map so we can build on it later
			matrix.put(student.getId(), sg);
		}
		stopwatch.timeWithContext("buildGradeMatrix", "matrix seeded", stopwatch.getTime());

		// get categories. This call is filtered for TAs as well.
		final List<CategoryDefinition> categories = this.getGradebookCategories();

		// for TA's, build a lookup map of visible categoryIds so we can filter
		// the assignment list to not fetch grades
		// for assignments we don't have category level access to.
		// for everyone else this will just be an empty list that is unused
		final List<Long> categoryIds = new ArrayList<>();

		if (role == GbRole.TA) {
			for (final CategoryDefinition category : categories) {
				categoryIds.add(category.getId());
			}
		}

		// this holds a map of categoryId and the list of assignment ids in each
		// we build this whilst iterating below to save further iterations when
		// building the category list
		final Map<Long, Set<Long>> categoryAssignments = new TreeMap<>();

		// iterate over assignments and get the grades for each
		// note, the returned list only includes entries where there is a grade
		// for the user
		// we also build the category lookup map here
		for (final Assignment assignment : assignments) {

			final Long categoryId = assignment.getCategoryId();
			final Long assignmentId = assignment.getId();

			// TA permission check. If there are categories and they don't have
			// access to this one, skip it
			if (role == GbRole.TA) {

				log.debug("TA processing category: {}", categoryId);

				if (!categoryIds.isEmpty() && categoryId != null && !categoryIds.contains(categoryId)) {
					continue;
				}
			}

			// TA stub out. So that we can support 'per grade' permissions for a
			// TA, we need a stub record for every student
			// This is because getGradesForStudentsForItem only returns records
			// where there is a grade (even if blank)
			// So this iteration for TAs allows the matrix to be fully
			// populated.
			// This is later updated to be a real grade entry if there is one.
			if (role == GbRole.TA) {
				for (final User student : students) {
					final GbStudentGradeInfo sg = matrix.get(student.getId());
					sg.addGrade(assignment.getId(), new GbGradeInfo(null));
				}
			}

			// build the category map (if assignment is categorised)
			if (categoryId != null) {
				Set<Long> values;
				if (categoryAssignments.containsKey(categoryId)) {
					values = categoryAssignments.get(categoryId);
					values.add(assignmentId);
				} else {
					values = new HashSet<>();
					values.add(assignmentId);
				}
				categoryAssignments.put(categoryId, values);
			}

			// get grades
			final List<GradeDefinition> defs = this.gradebookService.getGradesForStudentsForItem(gradebook.getUid(),
					assignment.getId(), studentUuids);
			stopwatch.timeWithContext("buildGradeMatrix", "getGradesForStudentsForItem: " + assignment.getId(),
					stopwatch.getTime());

			// iterate the definitions returned and update the record for each
			// student with the grades
			for (final GradeDefinition def : defs) {
				final GbStudentGradeInfo sg = matrix.get(def.getStudentUid());

				if (sg == null) {
					log.warn("No matrix entry seeded for: {}. This user may be been removed from the site", def.getStudentUid());
				} else {
					// this will overwrite the stub entry for the TA matrix if
					// need be
					sg.addGrade(assignment.getId(), new GbGradeInfo(def));
				}
			}
			stopwatch.timeWithContext("buildGradeMatrix", "updatedStudentGradeInfo: " + assignment.getId(),
					stopwatch.getTime());
		}
		stopwatch.timeWithContext("buildGradeMatrix", "matrix built", stopwatch.getTime());

		// build category columns
		for (final CategoryDefinition category : categories) {

			// use the category mappings for faster lookup of the assignmentIds
			// and grades in the category
			final Set<Long> categoryAssignmentIds = categoryAssignments.get(category.getId());

			// if there are no assignments in the category (ie its a new
			// category) this will be null, so skip
			if (categoryAssignmentIds != null) {

				for (final User student : students) {

					final GbStudentGradeInfo sg = matrix.get(student.getId());

					// get grades
					final Map<Long, GbGradeInfo> grades = sg.getGrades();

					// build map of just the grades we want
					final Map<Long, String> gradeMap = new HashMap<>();
					for (final Long assignmentId : categoryAssignmentIds) {
						final GbGradeInfo gradeInfo = grades.get(assignmentId);
						if (gradeInfo != null) {
							gradeMap.put(assignmentId, gradeInfo.getGrade());
						}
					}

					final Double categoryScore = this.gradebookService.calculateCategoryScore(gradebook,
							student.getId(), category, category.getAssignmentList(), gradeMap);

					// add to GbStudentGradeInfo
					sg.addCategoryAverage(category.getId(), categoryScore);

					// TODO the TA permission check could reuse this iteration... check performance.

				}
			}

		}
		stopwatch.timeWithContext("buildGradeMatrix", "categories built", stopwatch.getTime());

		// for a TA, apply the permissions to each grade item to see if we can render it
		// the list of students, assignments and grades is already filtered to those that can be viewed
		// so we are only concerned with the gradeable permission
		if (role == GbRole.TA) {

			// get permissions
			final List<PermissionDefinition> permissions = getPermissionsForUser(currentUserUuid);

			log.debug("All permissions: {}", permissions.size());

			// only need to process this if some are defined
			// again only concerned with grade permission, so parse the list to
			// remove those that aren't GRADE
			if (!permissions.isEmpty()) {

				final Iterator<PermissionDefinition> iter = permissions.iterator();
				while (iter.hasNext()) {
					final PermissionDefinition permission = iter.next();
					if (!StringUtils.equalsIgnoreCase(GraderPermission.GRADE.toString(), permission.getFunction())) {
						iter.remove();
					}
				}
			}

			log.debug("Filtered permissions: {}", permissions.size());

			// if we still have permissions, they will be of type grade, so we
			// need to enrich the students grades
			if (!permissions.isEmpty()) {

				// first need a lookup map of assignment id to category so we
				// can link up permissions by category
				final Map<Long, Long> assignmentCategoryMap = new HashMap<>();
				for (final Assignment assignment : assignments) {
					assignmentCategoryMap.put(assignment.getId(), assignment.getCategoryId());
				}

				// get the group membership for the students
				final Map<String, List<String>> groupMembershipsMap = getGroupMemberships();

				// for every student
				for (final User student : students) {

					log.debug("Processing student: {}", student.getEid());

					final GbStudentGradeInfo sg = matrix.get(student.getId());

					// get their assignment/grade list
					final Map<Long, GbGradeInfo> gradeMap = sg.getGrades();

					// for every assignment that has a grade
					for (final Map.Entry<Long, GbGradeInfo> entry : gradeMap.entrySet()) {

						// categoryId
						final Long gradeCategoryId = assignmentCategoryMap.get(entry.getKey());

						log.debug("Grade: {}", entry.getValue());

						// iterate the permissions
						// if category, compare the category,
						// then check the group and find the user in the group
						// if all ok, mark it as GRADEABLE

						boolean gradeable = false;

						for (final PermissionDefinition permission : permissions) {
							// we know they are all GRADE so no need to check
							// here

							boolean categoryOk = false;
							boolean groupOk = false;

							final Long permissionCategoryId = permission.getCategoryId();
							final String permissionGroupReference = permission.getGroupReference();

							log.debug("permissionCategoryId: {}", permissionCategoryId);
							log.debug("permissionGroupReference: {}", permissionGroupReference);

							// if permissions category is null (can grade all categories) or they match (can grade this category)
							if (!categoriesEnabled || (permissionCategoryId == null || permissionCategoryId.equals(gradeCategoryId))) {
								categoryOk = true;
								log.debug("Category check passed");
							}

							// if group reference is null (can grade all groups)
							// or group membership contains student (can grade
							// this group)
							if (StringUtils.isBlank(permissionGroupReference)) {
								groupOk = true;
								log.debug("Group check passed #1");
							} else {
								final List<String> groupMembers = groupMembershipsMap.get(permissionGroupReference);
								log.debug("groupMembers: {}", groupMembers);

								if (groupMembers != null && groupMembers.contains(student.getId())) {
									groupOk = true;
									log.debug("Group check passed #2");
								}
							}

							if (categoryOk && groupOk) {
								gradeable = true;
								break;
							}
						}

						// set the gradeable flag on this grade instance
						final GbGradeInfo gradeInfo = entry.getValue();
						gradeInfo.setGradeable(gradeable);
					}
				}
			}
			stopwatch.timeWithContext("buildGradeMatrix", "TA permissions applied", stopwatch.getTime());
		}

		// get the matrix as a list of GbStudentGradeInfo
		final List<GbStudentGradeInfo> items = new ArrayList<>(matrix.values());

		// sort the matrix based on the supplied assignment sort order (if any)
		if (settings.getAssignmentSortOrder() != null) {
			Comparator<GbStudentGradeInfo> comparator = new AssignmentGradeComparator(settings.getAssignmentSortOrder().getAssignmentId());

			final SortDirection direction = settings.getAssignmentSortOrder().getDirection();
			// reverse if required
			if (direction == SortDirection.DESCENDING) {
				comparator = Collections.reverseOrder(comparator);
			}
			// sort
			Collections.sort(items, comparator);
		}
		stopwatch.timeWithContext("buildGradeMatrix", "matrix sorted by assignment", stopwatch.getTime());

		// sort the matrix based on the supplied category sort order (if any)
		if (settings.getCategorySortOrder() != null) {
			Comparator comparator = new CategorySubtotalComparator(settings.getCategorySortOrder().getCategoryId());

			final SortDirection direction = settings.getCategorySortOrder().getDirection();
			// reverse if required
			if (direction == SortDirection.DESCENDING) {
				comparator = Collections.reverseOrder(comparator);
			}
			// sort
			Collections.sort(items, comparator);

		}
		stopwatch.timeWithContext("buildGradeMatrix", "matrix sorted by category", stopwatch.getTime());

		if (settings.getCourseGradeSortOrder() != null) {

			Comparator<GbStudentGradeInfo> comp = new CourseGradeComparator(getGradebookSettings());
			// reverse if required
			if (settings.getCourseGradeSortOrder() == SortDirection.DESCENDING) {
				comp = Collections.reverseOrder(comp);
			}
			// sort
			Collections.sort(items, comp);
		}
		stopwatch.timeWithContext("buildGradeMatrix", "matrix sorted by course grade", stopwatch.getTime());

		return items;
	}

	/**
	 * Get a list of sections and groups in a site
	 *
	 * @return
	 */
	public List<GbGroup> getSiteSectionsAndGroups() {
		final String siteId = getCurrentSiteId();

		final List<GbGroup> rval = new ArrayList<>();

		// get groups (handles both groups and sections)
		try {
			final Site site = this.siteService.getSite(siteId);
			final Collection<Group> groups = site.getGroups();

			for (final Group group : groups) {
				rval.add(new GbGroup(group.getId(), group.getTitle(), group.getReference(), GbGroup.Type.GROUP));
			}

		} catch (final IdUnusedException e) {
			// essentially ignore and use what we have
			log.error("Error retrieving groups", e);
		}

		GbRole role;
		try {
			role = this.getUserRole(siteId);
		} catch (final GbAccessDeniedException e) {
			log.warn("GbAccessDeniedException trying to getGradebookCategories", e);
			return rval;
		}

		// if user is a TA, get the groups they can see and filter the GbGroup
		// list to keep just those
		if (role == GbRole.TA) {
			final Gradebook gradebook = this.getGradebook(siteId);
			final User user = getCurrentUser();

			// need list of all groups as REFERENCES (not ids)
			final List<String> allGroupIds = new ArrayList<>();
			for (final GbGroup group : rval) {
				allGroupIds.add(group.getReference());
			}

			// get the ones the TA can actually view
			// note that if a group is empty, it will not be included.
			final List<String> viewableGroupIds = this.gradebookPermissionService
					.getViewableGroupsForUser(gradebook.getId(), user.getId(), allGroupIds);

			// remove the ones that the user can't view
			final Iterator<GbGroup> iter = rval.iterator();
			while (iter.hasNext()) {
				final GbGroup group = iter.next();
				if (!viewableGroupIds.contains(group.getReference())) {
					iter.remove();
				}
			}

		}

		Collections.sort(rval);

		return rval;
	}

	/**
	 * Helper to get siteid. This will ONLY work in a portal site context, it will return null otherwise (ie via an entityprovider).
	 *
	 * @return
	 */
	public String getCurrentSiteId() {
		try {
			return this.toolManager.getCurrentPlacement().getContext();
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * Helper to get site. This will ONLY work in a portal site context, it will return empty otherwise (ie via an entityprovider).
	 *
	 * @return
	 */
	public Optional<Site> getCurrentSite()
	{
		final String siteId = getCurrentSiteId();
		if (siteId != null)
		{
			try
			{
				return Optional.of(this.siteService.getSite(siteId));
			}
			catch (final IdUnusedException e)
			{
				// do nothing
			}
		}

		return Optional.empty();
	}

	/**
	 * Helper to get user
	 *
	 * @return
	 */
	public User getCurrentUser() {
		return this.userDirectoryService.getCurrentUser();
	}

	/**
	 * Determine if the current user is an admin user.
	 *
	 * @return true if the current user is admin, false otherwise.
	 */
	public boolean isSuperUser() {
		return this.securityService.isSuperUser();
	}

	/**
	 * Add a new assignment definition to the gradebook
	 *
	 * @param assignment
	 * @return id of the newly created assignment or null if there were any errors
	 */
	public Long addAssignment(final Assignment assignment) {

		final Gradebook gradebook = getGradebook();

		if (gradebook != null) {
			final String gradebookId = gradebook.getUid();

			final Long assignmentId = this.gradebookService.addAssignment(gradebookId, assignment);

			// Force the assignment to sit at the end of the list
			if (assignment.getSortOrder() == null) {
				final List<Assignment> allAssignments = this.gradebookService.getAssignments(gradebookId);
				int nextSortOrder = allAssignments.size();
				for (final Assignment anotherAssignment : allAssignments) {
					if (anotherAssignment.getSortOrder() != null && anotherAssignment.getSortOrder() >= nextSortOrder) {
						nextSortOrder = anotherAssignment.getSortOrder() + 1;
					}
				}
				updateAssignmentOrder(assignmentId, nextSortOrder);
			}

			// also update the categorized order
			updateAssignmentCategorizedOrder(gradebook.getUid(), assignment.getCategoryId(), assignmentId,
					Integer.MAX_VALUE);

			return assignmentId;

			// TODO wrap this so we can catch any runtime exceptions
		}
		return null;
	}

	/**
	 * Update the order of an assignment for the current site.
	 *
	 * @param assignmentId
	 * @param order
	 */
	public void updateAssignmentOrder(final long assignmentId, final int order) {

		final String siteId = getCurrentSiteId();
		this.updateAssignmentOrder(siteId, assignmentId, order);
	}

	/**
	 * Update the order of an assignment. If calling outside of GBNG, use this method as you can provide the site id.
	 *
	 * @param siteId the siteId
	 * @param assignmentId the assignment we are reordering
	 * @param order the new order
	 * @throws IdUnusedException
	 * @throws PermissionException
	 */
	public void updateAssignmentOrder(final String siteId, final long assignmentId, final int order) {

		final Gradebook gradebook = this.getGradebook(siteId);
		this.gradebookService.updateAssignmentOrder(gradebook.getUid(), assignmentId, order);
	}

	/**
	 * Update the categorized order of an assignment.
	 *
	 * @param assignmentId the assignment we are reordering
	 * @param order the new order
	 * @throws IdUnusedException
	 * @throws PermissionException
	 */
	public void updateAssignmentCategorizedOrder(final long assignmentId, final int order)
			throws IdUnusedException, PermissionException {
		final String siteId = getCurrentSiteId();
		updateAssignmentCategorizedOrder(siteId, assignmentId, order);
	}

	/**
	 * Update the categorized order of an assignment.
	 *
	 * @param siteId the site's id
	 * @param assignmentId the assignment we are reordering
	 * @param order the new order
	 * @throws IdUnusedException
	 * @throws PermissionException
	 */
	public void updateAssignmentCategorizedOrder(final String siteId, final long assignmentId, final int order)
			throws IdUnusedException, PermissionException {

		// validate site
		try {
			this.siteService.getSite(siteId);
		} catch (final IdUnusedException e) {
			log.warn("IdUnusedException trying to updateAssignmentCategorizedOrder", e);
			return;
		}

		final Gradebook gradebook = (Gradebook) this.gradebookService.getGradebook(siteId);

		if (gradebook == null) {
			log.error(String.format("Gradebook not in site %s", siteId));
			return;
		}

		final Assignment assignmentToMove = this.gradebookService.getAssignment(gradebook.getUid(), assignmentId);

		if (assignmentToMove == null) {
			// TODO Handle assignment not in gradebook
			log.error(String.format("GradebookAssignment %d not in site %s", assignmentId, siteId));
			return;
		}

		updateAssignmentCategorizedOrder(gradebook.getUid(), assignmentToMove.getCategoryId(), assignmentToMove.getId(),
				order);
	}

	/**
	 * Update the categorized order of an assignment via the gradebook service.
	 *
	 * @param gradebookId the gradebook's id
	 * @param categoryId the id for the cataegory in which we are reordering
	 * @param assignmentId the assignment we are reordering
	 * @param order the new order
	 */
	private void updateAssignmentCategorizedOrder(final String gradebookId, final Long categoryId,
			final Long assignmentId, final int order) {
		this.gradebookService.updateAssignmentCategorizedOrder(gradebookId, categoryId, assignmentId, order);
	}

	/**
	 * Get a list of edit events for this gradebook. Excludes any events for the current user
	 *
	 * @param gradebookUid the gradebook that we are interested in
	 * @param since the time to check for changes from
	 * @return
	 */
	public List<GbGradeCell> getEditingNotifications(final String gradebookUid, final Date since) {

		final User currentUser = getCurrentUser();

		final List<GbGradeCell> rval = new ArrayList<>();

		final List<Assignment> assignments = this.gradebookService.getViewableAssignmentsForCurrentUser(gradebookUid,
				SortType.SORT_BY_SORTING);
		final List<Long> assignmentIds = assignments.stream().map(a -> a.getId()).collect(Collectors.toList());
		final List<GradingEvent> events = this.gradebookService.getGradingEvents(assignmentIds, since);

		// keep a hash of all users so we don't have to hit the service each time
		final Map<String, GbUser> users = new HashMap<>();

		// filter out any events made by the current user
		for (final GradingEvent event : events) {
			if (!event.getGraderId().equals(currentUser.getId())) {
				// update cache (if required)
				users.putIfAbsent(event.getGraderId(), getUser(event.getGraderId()));

				// pull user from the cache
				final GbUser updatedBy = users.get(event.getGraderId());
				rval.add(
						new GbGradeCell(
								event.getStudentId(),
								event.getGradableObject().getId(),
								updatedBy.getDisplayName()));
			}
		}

		return rval;
	}

	/**
	 * Get an GradebookAssignment in the current site given the assignment id
	 *
	 * @param siteId
	 * @param assignmentId
	 * @return
	 */
	public Assignment getAssignment(final long assignmentId) {
		return this.getAssignment(getCurrentSiteId(), assignmentId);
	}

	/**
	 * Get an GradebookAssignment in the specified site given the assignment id
	 *
	 * @param siteId
	 * @param assignmentId
	 * @return
	 */
	public Assignment getAssignment(final String siteId, final long assignmentId) {
		final Gradebook gradebook = getGradebook(siteId);
		if (gradebook != null) {
			return this.gradebookService.getAssignment(gradebook.getUid(), assignmentId);
		}
		return null;
	}

	/**
	 * Get an GradebookAssignment in the current site given the assignment name This should be avoided where possible but is required for the import
	 * process to allow modification of assignment point values
	 *
	 * @param assignmentName
	 * @return
	 */
	public Assignment getAssignment(final String assignmentName) {
		return this.getAssignment(getCurrentSiteId(), assignmentName);
	}

	/**
	 * Get an GradebookAssignment in the specified site given the assignment name This should be avoided where possible but is required for the
	 * import process to allow modification of assignment point values
	 *
	 * @param siteId
	 * @param assignmentName
	 * @return
	 */
	@SuppressWarnings("deprecation")
	public Assignment getAssignment(final String siteId, final String assignmentName) {
		final Gradebook gradebook = getGradebook(siteId);
		if (gradebook != null) {
			return this.gradebookService.getAssignment(gradebook.getUid(), assignmentName);
		}
		return null;
	}

	/**
	 * Get the sort order of an assignment. If the assignment has a sort order, use that. Otherwise we determine the order of the assignment
	 * in the list of assignments
	 *
	 * This means that we can always determine the most current sort order for an assignment, even if the list has never been sorted.
	 *
	 *
	 * @param assignmentId
	 * @return sort order if set, or calculated, or -1 if cannot determine at all.
	 */
	public int getAssignmentSortOrder(final long assignmentId) {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		if (gradebook != null) {
			final Assignment assignment = this.gradebookService.getAssignment(gradebook.getUid(), assignmentId);

			// if the assignment has a sort order, return that
			if (assignment.getSortOrder() != null) {
				return assignment.getSortOrder();
			}

			// otherwise we need to determine the assignment sort order within
			// the list of assignments
			final List<Assignment> assignments = this.getGradebookAssignments(siteId);

			for (int i = 0; i < assignments.size(); i++) {
				final Assignment a = assignments.get(i);
				if (assignmentId == a.getId() && a.getSortOrder() != null) {
					return a.getSortOrder();
				}
			}
		}

		return -1;
	}

	/**
	 * Update the details of an assignment
	 *
	 * @param assignment
	 * @return
	 */
	public boolean updateAssignment(final Assignment assignment) {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		// need the original name as the service needs that as the key...
		final Assignment original = this.getAssignment(assignment.getId());

		try {
			this.gradebookService.updateAssignment(gradebook.getUid(), original.getId(), assignment);
			if (original.getCategoryId() != null && assignment.getCategoryId() != null
					&& original.getCategoryId().longValue() != assignment.getCategoryId().longValue()) {
				updateAssignmentCategorizedOrder(gradebook.getUid(), assignment.getCategoryId(), assignment.getId(),
						Integer.MAX_VALUE);
			}
			return true;
		} catch (final Exception e) {
			log.error("An error occurred updating the assignment", e);
		}

		return false;
	}

	/**
	 * Updates ungraded items in the given assignment with the given grade
	 *
	 * @param assignmentId
	 * @param grade
	 * @return
	 */
	public boolean updateUngradedItems(final long assignmentId, final double grade) {
		return updateUngradedItems(assignmentId, grade, null);
	}

	/**
	 * Updates ungraded items in the given assignment for students within a particular group and with the given grade
	 *
	 * @param assignmentId
	 * @param grade
	 * @param group
	 * @return
	 */
	public boolean updateUngradedItems(final long assignmentId, final double grade, final GbGroup group) {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		// get students
		final List<String> studentUuids = (group == null) ? this.getGradeableUsers() : this.getGradeableUsers(group);

		// get grades (only returns those where there is a grade)
		final List<GradeDefinition> defs = this.gradebookService.getGradesForStudentsForItem(gradebook.getUid(),
				assignmentId, studentUuids);

		// iterate and trim the studentUuids list down to those that don't have
		// grades
		for (final GradeDefinition def : defs) {

			// don't remove those where the grades are blank, they need to be
			// updated too
			if (StringUtils.isNotBlank(def.getGrade())) {
				studentUuids.remove(def.getStudentUid());
			}
		}

		if (studentUuids.isEmpty()) {
			log.debug("Setting default grade. No students are ungraded.");
		}

		try {
			// for each student remaining, add the grade
			for (final String studentUuid : studentUuids) {

				log.debug("Setting default grade. Values of assignmentId: {}, studentUuid: {}, grade: {}", assignmentId, studentUuid, grade);

				// TODO if this is slow doing it one by one, might be able to
				// batch it
				this.gradebookService.saveGradeAndCommentForStudent(gradebook.getUid(), assignmentId, studentUuid,
						FormatHelper.formatGradeForDisplay(String.valueOf(grade)), null);
			}
			return true;
		} catch (final Exception e) {
			log.error("An error occurred updating the assignment", e);
		}

		return false;
	}

	/**
	 * Get the grade log for the given student and assignment
	 *
	 * @param studentUuid
	 * @param assignmentId
	 * @return
	 */
	public List<GbGradeLog> getGradeLog(final String studentUuid, final long assignmentId) {
		final List<GradingEvent> gradingEvents = this.gradebookService.getGradingEvents(studentUuid, assignmentId);

		final List<GbGradeLog> rval = new ArrayList<>();
		for (final GradingEvent ge : gradingEvents) {
			rval.add(new GbGradeLog(ge));
		}

		Collections.reverse(rval);

		return rval;
	}

	/**
	 * Get the user given a uuid
	 *
	 * @param userUuid
	 * @return GbUser or null if cannot be found
	 */
	public GbUser getUser(final String userUuid) {
		try {
			final User u = this.userDirectoryService.getUser(userUuid);
			return new GbUser(u);
		} catch (final UserNotDefinedException e) {
			return null;
		}
	}

	/**
	 * Get the comment for a given student assignment grade
	 *
	 * @param assignmentId id of assignment
	 * @param studentUuid uuid of student
	 * @return the comment or null if none
	 */
	public String getAssignmentGradeComment(final long assignmentId, final String studentUuid) {
		return getAssignmentGradeComment(getCurrentSiteId(), assignmentId, studentUuid);
	}

	/**
	 * Get the comment for a given student assignment grade
	 *
	 * @param siteId site id
	 * @param assignmentId id of assignment
	 * @param studentUuid uuid of student
	 * @return the comment or null if none
	 */
	public String getAssignmentGradeComment(final String siteId, final long assignmentId, final String studentUuid) {
		final Gradebook gradebook = getGradebook(siteId);

		try {
			final CommentDefinition def = this.gradebookService.getAssignmentScoreComment(gradebook.getUid(),
					assignmentId, studentUuid);
			if (def != null) {
				return def.getCommentText();
			}
		} catch (GradebookNotFoundException | AssessmentNotFoundException e) {
			log.error("An error occurred retrieving the comment. {}: {}", e.getClass(), e.getMessage());
		}
		return null;
	}

	/**
	 * Update (or set) the comment for a student's assignment
	 *
	 * @param assignmentId id of assignment
	 * @param studentUuid uuid of student
	 * @param comment the comment
	 * @return true/false
	 */
	public boolean updateAssignmentGradeComment(final long assignmentId, final String studentUuid,
			final String comment) {

		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		try {
			// could do a check here to ensure we aren't overwriting someone
			// else's comment that has been updated in the interim...
			this.gradebookService.setAssignmentScoreComment(gradebook.getUid(), assignmentId, studentUuid, comment);
			return true;
		} catch (GradebookNotFoundException | AssessmentNotFoundException | IllegalArgumentException e) {
			log.error("An error occurred saving the comment. {}: {}", e.getClass(), e.getMessage());
		}

		return false;
	}

	/**
	 * Get the role of the current user in the current site
	 *
	 * @return GbRole
	 * @throws GbAccessDeniedException if something goes wrong checking the site or user permissions
	 */
	public GbRole getUserRole() throws GbAccessDeniedException {
		final String siteId = getCurrentSiteId();
		return this.getUserRole(siteId);
	}

	/**
	 * Get the role of the current user in the given site
	 *
	 * @param siteId the siteId to check
	 * @return GbRole for the current user
	 * @throws GbAccessDeniedException if something goes wrong checking the site or user permissions
	 */
	public GbRole getUserRole(final String siteId) throws GbAccessDeniedException {

		final String userId = getCurrentUser().getId();

		String siteRef;
		try {
			siteRef = this.siteService.getSite(siteId).getReference();
		} catch (final IdUnusedException e) {
			throw new GbAccessDeniedException(e);
		}

		GbRole rval;

		if (this.securityService.unlock(userId, GbRole.INSTRUCTOR.getValue(), siteRef)) {
			rval = GbRole.INSTRUCTOR;
		} else if (this.securityService.unlock(userId, GbRole.TA.getValue(), siteRef)) {
			rval = GbRole.TA;
		} else if (this.securityService.unlock(userId, GbRole.STUDENT.getValue(), siteRef)) {
			rval = GbRole.STUDENT;
		} else {
			throw new GbAccessDeniedException("Current user does not have a valid section.role.x permission");
		}

		return rval;
	}

	/**
	 * Get a map of grades for the given student. Safe to call when logged in as a student.
	 *
	 * @param studentUuid
	 * @return map of assignment to GbGradeInfo
	 */
	public Map<Long, GbGradeInfo> getGradesForStudent(final String studentUuid) {

		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		// will apply permissions and only return those the student can view
		final List<Assignment> assignments = getGradebookAssignmentsForStudent(studentUuid);

		final Map<Long, GbGradeInfo> rval = new LinkedHashMap<>();

		// iterate all assignments and get the grades
		// if student, only proceed if grades are released for the site
		// if instructor or TA, skip this check
		// permission checks are still applied at the assignment level in the
		// GradebookService
		GbRole role;
		try {
			role = this.getUserRole(siteId);
		} catch (final GbAccessDeniedException e) {
			log.warn("GbAccessDeniedException trying to getGradesForStudent", e);
			return rval;
		}

		if (role == GbRole.STUDENT) {
			final boolean released = gradebook.isAssignmentsDisplayed();
			if (!released) {
				return rval;
			}
		}

		for (final Assignment assignment : assignments) {
			final GradeDefinition def = this.gradebookService.getGradeDefinitionForStudentForItem(gradebook.getUid(),
					assignment.getId(), studentUuid);
			rval.put(assignment.getId(), new GbGradeInfo(def));
		}

		return rval;
	}

	/**
	 * Get the category score for the given student. Safe to call when logged in as a student.
	 *
	 * @param categoryId id of category
	 * @param studentUuid uuid of student
	 * @return
	 */
	public Double getCategoryScoreForStudent(final Long categoryId, final String studentUuid) {

		final Gradebook gradebook = getGradebook();

		final Double score = this.gradebookService.calculateCategoryScore(gradebook.getId(), studentUuid, categoryId);
		log.info("Category score for category: {}, student: {}:{}", categoryId, studentUuid, score);

		return score;
	}

	/**
	 * Get the settings for this gradebook. Note that this CANNOT be called by a student nor by an entityprovider
	 *
	 * @return
	 */
	public GradebookInformation getGradebookSettings() {
		return getGradebookSettings(getCurrentSiteId());
	}

	/**
	 * Get the settings for this gradebook. Note that this CANNOT be called by a student. Safe to use from an entityprovider.
	 * 
	 * @return
	 */
	public GradebookInformation getGradebookSettings(final String siteId) {
		final Gradebook gradebook = getGradebook(siteId);

		final GradebookInformation settings = this.gradebookService.getGradebookInformation(gradebook.getUid());

		Collections.sort(settings.getCategories(), CategoryDefinition.orderComparator);

		return settings;
	}

	/**
	 * Update the settings for this gradebook
	 *
	 * @param settings GradebookInformation settings
	 */
	public void updateGradebookSettings(final GradebookInformation settings) {

		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		this.gradebookService.updateGradebookSettings(gradebook.getUid(), settings);
	}

	/**
	 * Remove an assignment from its gradebook
	 *
	 * @param assignmentId the id of the assignment to remove
	 */
	public void removeAssignment(final Long assignmentId) {
		this.gradebookService.removeAssignment(assignmentId);
	}

	/**
	 * Get a list of teaching assistants in the current site
	 *
	 * @return
	 */
	public List<GbUser> getTeachingAssistants() {

		final String siteId = getCurrentSiteId();
		final List<GbUser> rval = new ArrayList<>();

		try {
			final Set<String> userUuids = this.siteService.getSite(siteId).getUsersIsAllowed(GbRole.TA.getValue());
			for (final String userUuid : userUuids) {
				rval.add(getUser(userUuid));
			}
		} catch (final IdUnusedException e) {
			log.warn("IdUnusedException trying to getTeachingAssistants", e);
		}

		return rval;
	}

	/**
	 * Get a list of permissions defined for the given user. Note: These are currently only defined/used for a teaching assistant.
	 *
	 * @param userUuid
	 * @return list of permissions or empty list if none
	 */
	public List<PermissionDefinition> getPermissionsForUser(final String userUuid) {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		final List<PermissionDefinition> permissions = this.gradebookPermissionService
				.getPermissionsForUser(gradebook.getUid(), userUuid);
		if (permissions == null) {
			return new ArrayList<>();
		}
		return permissions;
	}

	/**
	 * Update the permissions for the user. Note: These are currently only defined/used for a teaching assistant.
	 *
	 * @param userUuid
	 * @param permissions
	 */
	public void updatePermissionsForUser(final String userUuid, final List<PermissionDefinition> permissions) {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		this.gradebookPermissionService.updatePermissionsForUser(gradebook.getUid(), userUuid, permissions);
	}

	/**
	 * Check if the course grade is visible to the user
	 *
	 * For TA's, the students are already filtered by permission so the TA won't see those they don't have access to anyway However if there
	 * are permissions and the course grade checkbox is NOT checked, then they explicitly do not have access to the course grade. So this
	 * method checks if the TA has any permissions assigned for the site, and if one of them is the course grade permission, then they have
	 * access.
	 *
	 * @param userUuid user to check
	 * @return boolean
	 */
	public boolean isCourseGradeVisible(final String userUuid) {

		final String siteId = getCurrentSiteId();

		GbRole role;
		try {
			role = this.getUserRole(siteId);
		} catch (final GbAccessDeniedException e) {
			log.warn("GbAccessDeniedException trying to check isCourseGradeVisible", e);
			return false;
		}

		// if instructor, allowed
		if (role == GbRole.INSTRUCTOR) {
			return true;
		}

		// if TA, permission checks
		if (role == GbRole.TA) {

			// if no defs, implicitly allowed
			final List<PermissionDefinition> defs = getPermissionsForUser(userUuid);
			if (defs.isEmpty()) {
				return true;
			}

			// if defs and one is the view course grade, explicitly allowed
			for (final PermissionDefinition def : defs) {
				if (StringUtils.equalsIgnoreCase(def.getFunction(), GraderPermission.VIEW_COURSE_GRADE.toString())) {
					return true;
				}
			}
			return false;
		}

		// if student, check the settings
		// this could actually get the settings but it would be more processing
		if (role == GbRole.STUDENT) {
			final Gradebook gradebook = this.getGradebook(siteId);

			if (gradebook.isCourseGradeDisplayed()) {
				return true;
			}
		}

		// other roles not yet catered for, catch all.
		log.warn("User: {} does not have a valid Gradebook related role in site: {}", userUuid, siteId);
		return false;
	}

	/**
	 * Are student numbers visible to the current user in the current site?
	 *
	 * @return true if student numbers are visible
	 */
	public boolean isStudentNumberVisible()
	{
		if (getCandidateDetailProvider() == null) {
			return false;
		}

		final User user = getCurrentUser();
		final Optional<Site> site = getCurrentSite();
		return user != null && site.isPresent() && getCandidateDetailProvider().isInstitutionalNumericIdEnabled(site.get())
				&& this.gradebookService.currentUserHasViewStudentNumbersPerm(getGradebook().getUid());
	}

	public String getStudentNumber(final User u, final Site site)
	{
		if (site == null || getCandidateDetailProvider() == null)
		{
			return "";
		}

		return getCandidateDetailProvider().getInstitutionalNumericId(u, site).orElse("");
	}

	/**
	 * Build a list of group references to site membership (as uuids) for the groups that are viewable for the current user.
	 *
	 * @return
	 */
	public Map<String, List<String>> getGroupMemberships() {

		final String siteId = getCurrentSiteId();

		Site site;
		try {
			site = this.siteService.getSite(siteId);
		} catch (final IdUnusedException e) {
			log.error("Error looking up site: {}", siteId, e);
			return null;
		}

		// filtered for the user
		final List<GbGroup> viewableGroups = getSiteSectionsAndGroups();

		final Map<String, List<String>> rval = new HashMap<>();

		for (final GbGroup gbGroup : viewableGroups) {
			final String groupReference = gbGroup.getReference();
			final List<String> memberUuids = new ArrayList<>();

			final Group group = site.getGroup(groupReference);
			if (group != null) {
				final Set<Member> members = group.getMembers();

				for (final Member m : members) {
					memberUuids.add(m.getUserId());
				}
			}

			rval.put(groupReference, memberUuids);

		}

		return rval;
	}

	/**
	 * Have categories been enabled for the gradebook?
	 *
	 * @return if the gradebook is setup for either "Categories Only" or "Categories & Weighting"
	 */
	public boolean categoriesAreEnabled() {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		return GbCategoryType.ONLY_CATEGORY.getValue() == gradebook.getCategory_type()
				|| GbCategoryType.WEIGHTED_CATEGORY.getValue() == gradebook.getCategory_type();
	}

	/**
	 * Get the currently configured gradebook category type
	 *
	 * @return GbCategoryType wrapper around the int value
	 */
	public GbCategoryType getGradebookCategoryType() {
		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		final int configuredType = gradebook.getCategory_type();

		return GbCategoryType.valueOf(configuredType);
	}

	/**
	 * Update the course grade (override) for this student
	 *
	 * @param studentUuid uuid of the student
	 * @param grade the new grade
	 * @return
	 */
	public boolean updateCourseGrade(final String studentUuid, final String grade) {

		final String siteId = getCurrentSiteId();
		final Gradebook gradebook = getGradebook(siteId);

		try {
			this.gradebookService.updateCourseGradeForStudent(gradebook.getUid(), studentUuid, grade);
			return true;
		} catch (final Exception e) {
			log.error("An error occurred saving the course grade. {}: {}", e.getClass(), e.getMessage());
		}

		return false;
	}

	/**
	 * Get the user's preferred locale from the Sakai resource loader
	 *
	 * @return
	 */
	public Locale getUserPreferredLocale() {
		final ResourceLoader rl = new ResourceLoader();
		return rl.getLocale();
	}

	/**
	 * Helper to check if a user is roleswapped
	 *
	 * @return true if ja, false if nay.
	 */
	public boolean isUserRoleSwapped() {
		try {
			return this.securityService.isUserRoleSwapped();
		} catch (final IdUnusedException e) {
			// something has happened between getting the siteId and getting the site.
			throw new GbException("An error occurred checking some bits and pieces, please try again.", e);
		}
	}

	/**
	 * Helper to determine the icon class to use depending on the assignment external source
	 *
	 * @param assignment
	 * @return
	 */
	public String getIconClass(final Assignment assignment) {
		final String externalAppName = assignment.getExternalAppName();

		String iconClass = getDefaultIconClass();
		if (StringUtils.equals(externalAppName, externalAppLoader.getString("sakai.assignment.title"))) {
			iconClass = getAssignmentsIconClass();
		} else if (StringUtils.equals(externalAppName, externalAppLoader.getString("sakai.samigo.title"))) {
			iconClass = getSamigoIconClass();
		} else if (StringUtils.equals(externalAppName, externalAppLoader.getString("sakai.lessonbuildertool.title"))) {
			iconClass = getLessonBuilderIconClass();
		}
		return iconClass;
	}

	/**
	 * Helper to determine the icon class for possible external app names
	 *
	 * @return
	 */
	public Map<String, String> getIconClassMap() {
		final Map<String, String> mapping = new HashMap<>();

		final Tool assignment = this.toolManager.getTool("sakai.assignment.grades");
		if (assignment != null) {
			mapping.put(assignment.getTitle(), getAssignmentsIconClass());
		}

		final Tool samigo = this.toolManager.getTool("sakai.samigo");
		if (samigo != null) {
			mapping.put(samigo.getTitle(), getSamigoIconClass());
		}

		mapping.put("Lesson Builder", getLessonBuilderIconClass());

		return mapping;
	}

	public String getDefaultIconClass() {
		return ICON_SAKAI + "default-tool fa fa-globe";
	}

	private String getAssignmentsIconClass() {
		return ICON_SAKAI + "sakai-assignment-grades";
	}

	private String getSamigoIconClass() {
		return ICON_SAKAI + "sakai-samigo";
	}

	private String getLessonBuilderIconClass() {
		return ICON_SAKAI + "sakai-lessonbuildertool";
	}

	// Return a CandidateDetailProvider or null if it's not enabled
	private CandidateDetailProvider getCandidateDetailProvider() {
		return (CandidateDetailProvider)ComponentManager.get("org.sakaiproject.user.api.CandidateDetailProvider");
	}
}