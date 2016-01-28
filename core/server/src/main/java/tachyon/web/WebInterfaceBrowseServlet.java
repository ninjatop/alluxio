/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.Lists;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.client.ReadType;
import tachyon.client.file.FileInStream;
import tachyon.client.file.FileSystem;
import tachyon.client.file.URIStatus;
import tachyon.client.file.options.OpenFileOptions;
import tachyon.conf.TachyonConf;
import tachyon.exception.AccessControlException;
import tachyon.exception.FileDoesNotExistException;
import tachyon.exception.InvalidPathException;
import tachyon.exception.TachyonException;
import tachyon.master.TachyonMaster;
import tachyon.security.LoginUser;
import tachyon.security.authentication.PlainSaslServer;
import tachyon.thrift.BlockLocation;
import tachyon.thrift.FileBlockInfo;
import tachyon.thrift.FileInfo;
import tachyon.thrift.WorkerNetAddress;
import tachyon.util.SecurityUtils;
import tachyon.util.io.PathUtils;

/**
 * Servlet that provides data for browsing the file system.
 */
@ThreadSafe
public final class WebInterfaceBrowseServlet extends HttpServlet {

  private static final long serialVersionUID = 6121623049981468871L;

  private final transient TachyonMaster mMaster;
  private final transient TachyonConf mTachyonConf;

  /**
   * Creates a new instance of {@link WebInterfaceBrowseServlet}.
   *
   * @param master the Tachyon master
   */
  public WebInterfaceBrowseServlet(TachyonMaster master) {
    mMaster = master;
    mTachyonConf = new TachyonConf();
  }

  /**
   * This function displays 5KB of a file from a specific offset if it is in ASCII format.
   *
   * @param path the path of the file to display
   * @param request the {@link HttpServletRequest} object
   * @param offset where the file starts to display
   * @throws FileDoesNotExistException if the file does not exist
   * @throws IOException if an I/O error occurs
   * @throws InvalidPathException if an invalid path is encountered
   */
  private void displayFile(TachyonURI path, HttpServletRequest request, long offset)
      throws FileDoesNotExistException, InvalidPathException, IOException, TachyonException {
    FileSystem fs = FileSystem.Factory.get();
    String fileData = null;
    URIStatus status = fs.getStatus(path);
    if (status.isCompleted()) {
      OpenFileOptions options = OpenFileOptions.defaults().setReadType(ReadType.NO_CACHE);
      FileInStream is = fs.openFile(path, options);
      try {
        int len = (int) Math.min(5 * Constants.KB, status.getLength() - offset);
        byte[] data = new byte[len];
        long skipped = is.skip(offset);
        if (skipped < 0) {
          // nothing was skipped
          fileData = "Unable to traverse to offset; is file empty?";
        } else if (skipped < offset) {
          // couldn't skip all the way to offset
          fileData = "Unable to traverse to offset; is offset larger than the file?";
        } else {
          // read may not read up to len, so only convert what was read
          int read = is.read(data, 0, len);
          if (read < 0) {
            // stream couldn't read anything, skip went to EOF?
            fileData = "Unable to read file";
          } else {
            fileData = WebUtils.convertByteArrayToStringWithoutEscape(data, 0, read);
          }
        }
      } finally {
        is.close();
      }
    } else {
      fileData = "The requested file is not complete yet.";
    }
    List<UIFileBlockInfo> uiBlockInfo = new ArrayList<UIFileBlockInfo>();
    for (FileBlockInfo fileBlockInfo : mMaster.getFileSystemMaster().getFileBlockInfoList(path)) {
      uiBlockInfo.add(new UIFileBlockInfo(fileBlockInfo));
    }
    request.setAttribute("fileBlocks", uiBlockInfo);
    request.setAttribute("fileData", fileData);
    request.setAttribute("highestTierAlias", mMaster.getBlockMaster().getGlobalStorageTierAssoc()
        .getAlias(0));
  }

  /**
   * Populates attribute fields with data from the MasterInfo associated with this servlet. Errors
   * will be displayed in an error field. Debugging can be enabled to display additional data. Will
   * eventually redirect the request to a jsp.
   *
   * @param request the {@link HttpServletRequest} object
   * @param response the {@link HttpServletResponse} object
   * @throws ServletException if the target resource throws this exception
   * @throws IOException if the target resource throws this exception
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    if (SecurityUtils.isSecurityEnabled(mTachyonConf)
        && PlainSaslServer.AuthorizedClientUser.get(mTachyonConf) == null) {
      PlainSaslServer.AuthorizedClientUser.set(LoginUser.get(mTachyonConf).getName());
    }
    request.setAttribute("debug", Constants.DEBUG);
    request.setAttribute("viewLog", false);

    request.setAttribute("masterNodeAddress", mMaster.getMasterAddress().toString());
    request.setAttribute("invalidPathError", "");
    List<FileInfo> filesInfo;
    String requestPath = request.getParameter("path");
    if (requestPath == null || requestPath.isEmpty()) {
      requestPath = TachyonURI.SEPARATOR;
    }
    TachyonURI currentPath = new TachyonURI(requestPath);
    request.setAttribute("currentPath", currentPath.toString());
    request.setAttribute("viewingOffset", 0);

    try {
      long fileId = mMaster.getFileSystemMaster().getFileId(currentPath);
      FileInfo fileInfo = mMaster.getFileSystemMaster().getFileInfo(fileId);
      UIFileInfo currentFileInfo = new UIFileInfo(fileInfo);
      if (currentFileInfo.getAbsolutePath() == null) {
        throw new FileDoesNotExistException(currentPath.toString());
      }
      request.setAttribute("currentDirectory", currentFileInfo);
      request.setAttribute("blockSizeBytes", currentFileInfo.getBlockSizeBytes());
      request.setAttribute("workerWebPort", mTachyonConf.getInt(Constants.WORKER_WEB_PORT));
      if (!currentFileInfo.getIsDirectory()) {
        String offsetParam = request.getParameter("offset");
        long relativeOffset = 0;
        long offset;
        try {
          if (offsetParam != null) {
            relativeOffset = Long.parseLong(offsetParam);
          }
        } catch (NumberFormatException e) {
          relativeOffset = 0;
        }
        String endParam = request.getParameter("end");
        // If no param "end" presents, the offset is relative to the beginning; otherwise, it is
        // relative to the end of the file.
        if (endParam == null) {
          offset = relativeOffset;
        } else {
          offset = fileInfo.getLength() - relativeOffset;
        }
        if (offset < 0) {
          offset = 0;
        } else if (offset > fileInfo.getLength()) {
          offset = fileInfo.getLength();
        }
        try {
          displayFile(new TachyonURI(currentFileInfo.getAbsolutePath()), request, offset);
        } catch (TachyonException e) {
          throw new IOException(e);
        }
        request.setAttribute("viewingOffset", offset);
        getServletContext().getRequestDispatcher("/viewFile.jsp").forward(request, response);
        return;
      }
      setPathDirectories(currentPath, request);
      filesInfo = mMaster.getFileSystemMaster().getFileInfoList(currentPath);
    } catch (FileDoesNotExistException e) {
      request.setAttribute("invalidPathError", "Error: Invalid Path " + e.getMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    } catch (InvalidPathException e) {
      request.setAttribute("invalidPathError", "Error: Invalid Path " + e.getLocalizedMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    } catch (IOException e) {
      request.setAttribute("invalidPathError",
          "Error: File " + currentPath + " is not available " + e.getMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    } catch (AccessControlException e) {
      request.setAttribute("invalidPathError",
          "Error: File " + currentPath + " cannot be accessed " + e.getMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    }

    List<UIFileInfo> fileInfos = new ArrayList<UIFileInfo>(filesInfo.size());
    for (FileInfo fileInfo : filesInfo) {
      UIFileInfo toAdd = new UIFileInfo(fileInfo);
      try {
        if (!toAdd.getIsDirectory() && fileInfo.getLength() > 0) {
          FileBlockInfo blockInfo =
              mMaster.getFileSystemMaster()
                  .getFileBlockInfoList(new TachyonURI(toAdd.getAbsolutePath())).get(0);
          List<WorkerNetAddress> addrs = Lists.newArrayList();
          // add the in-memory block locations
          for (BlockLocation location : blockInfo.getBlockInfo().getLocations()) {
            addrs.add(location.getWorkerAddress());
          }
          // add underFS locations
          addrs.addAll(blockInfo.getUfsLocations());
          toAdd.setFileLocations(addrs);
        }
      } catch (FileDoesNotExistException e) {
        request.setAttribute("FileDoesNotExistException",
            "Error: non-existing file " + e.getMessage());
        getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
        return;
      } catch (InvalidPathException e) {
        request.setAttribute("InvalidPathException",
            "Error: invalid path " + e.getMessage());
        getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      }
      fileInfos.add(toAdd);
    }
    Collections.sort(fileInfos, UIFileInfo.PATH_STRING_COMPARE);

    request.setAttribute("nTotalFile", fileInfos.size());

    // URL can not determine offset and limit, let javascript in jsp determine and redirect
    if (request.getParameter("offset") == null && request.getParameter("limit") == null) {
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    }

    try {
      int offset = Integer.parseInt(request.getParameter("offset"));
      int limit = Integer.parseInt(request.getParameter("limit"));
      List<UIFileInfo> sub = fileInfos.subList(offset, offset + limit);
      request.setAttribute("fileInfos", sub);
    } catch (NumberFormatException e) {
      request.setAttribute("fatalError",
          "Error: offset or limit parse error, " + e.getLocalizedMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    } catch (IndexOutOfBoundsException e) {
      request.setAttribute("fatalError",
          "Error: offset or offset + limit is out of bound, " + e.getLocalizedMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    } catch (IllegalArgumentException e) {
      request.setAttribute("fatalError", e.getLocalizedMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
      return;
    }

    getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
  }

  /**
   * This function sets the file information for directories that are in the path to the current
   * directory.
   *
   * @param path the path of the current directory
   * @param request the {@link HttpServletRequest} object
   * @throws FileDoesNotExistException if the file does not exist
   * @throws InvalidPathException if an invalid path is encountered
   * @throws AccessControlException if permission checking fails
   */
  private void setPathDirectories(TachyonURI path, HttpServletRequest request)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException {
    if (path.isRoot()) {
      request.setAttribute("pathInfos", new UIFileInfo[0]);
      return;
    }

    String[] splitPath = PathUtils.getPathComponents(path.toString());
    UIFileInfo[] pathInfos = new UIFileInfo[splitPath.length - 1];
    TachyonURI currentPath = new TachyonURI(TachyonURI.SEPARATOR);
    long fileId = mMaster.getFileSystemMaster().getFileId(currentPath);
    pathInfos[0] = new UIFileInfo(mMaster.getFileSystemMaster().getFileInfo(fileId));
    for (int i = 1; i < splitPath.length - 1; i ++) {
      currentPath = currentPath.join(splitPath[i]);
      fileId = mMaster.getFileSystemMaster().getFileId(currentPath);
      pathInfos[i] = new UIFileInfo(mMaster.getFileSystemMaster().getFileInfo(fileId));
    }
    request.setAttribute("pathInfos", pathInfos);
  }
}
